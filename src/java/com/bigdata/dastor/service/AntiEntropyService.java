/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bigdata.dastor.service;

import java.io.*;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

import org.apache.log4j.Logger;

import com.bigdata.dastor.concurrent.StageManager;
import com.bigdata.dastor.config.DatabaseDescriptor;
import com.bigdata.dastor.db.ColumnFamilyStore;
import com.bigdata.dastor.db.CompactionManager;
import com.bigdata.dastor.db.DecoratedKey;
import com.bigdata.dastor.db.Table;
import com.bigdata.dastor.dht.Range;
import com.bigdata.dastor.dht.Token;
import com.bigdata.dastor.io.CompactionIterator.CompactedRow;
import com.bigdata.dastor.io.ICompactSerializer;
import com.bigdata.dastor.io.IndexSummary;
import com.bigdata.dastor.io.SSTableReader;
import com.bigdata.dastor.net.IVerbHandler;
import com.bigdata.dastor.net.Message;
import com.bigdata.dastor.net.MessagingService;
import com.bigdata.dastor.streaming.StreamOut;
import com.bigdata.dastor.streaming.StreamOutManager;
import com.bigdata.dastor.utils.*;

/**
 * AntiEntropyService encapsulates "validating" (hashing) individual column families,
 * exchanging MerkleTrees with remote nodes via a TreeRequest/Response conversation,
 * and then triggering repairs for disagreeing ranges.
 *
 * Every Tree conversation has an 'initiator', where valid trees are sent after generation
 * and where the local and remote tree will rendezvous in rendezvous(cf, endpoint, tree).
 * Once the trees rendezvous, a Differencer is executed and the service can trigger repairs
 * for disagreeing ranges.
 *
 * Tree comparison and repair triggering occur in the single threaded AE_SERVICE_STAGE.
 *
 * The steps taken to enact a repair are as follows:
 * 1. A repair is triggered via nodeprobe:
 *   * Nodeprobe sends TreeRequest messages to all neighbors of the target node: when a node
 *     receives a TreeRequest, it will perform a readonly compaction to immediately validate
 *     the column family.
 * 2. The compaction process validates the column family by:
 *   * Calling Validator.prepare(), which samples the column family to determine key distribution,
 *   * Calling Validator.add() in order for every row in the column family,
 *   * Calling Validator.complete() to indicate that all rows have been added.
 *     * Calling complete() indicates that a valid MerkleTree has been created for the column family.
 *     * The valid tree is returned to the requesting node via a TreeResponse.
 * 3. When a node receives a TreeResponse, it passes the tree to rendezvous(), which checks for trees to
 *    rendezvous with / compare to:
 *   * If the tree is local, it is cached, and compared to any trees that were received from neighbors.
 *   * If the tree is remote, it is immediately compared to a local tree if one is cached. Otherwise,
 *     the remote tree is stored until a local tree can be generated.
 *   * A Differencer object is enqueued for each comparison.
 * 4. Differencers are executed in AE_SERVICE_STAGE, to compare the two trees, and perform repair via the
 *    streaming api.
 */
public class AntiEntropyService
{
    private static final Logger logger = Logger.getLogger(AntiEntropyService.class);

    // timeout for outstanding requests (48 hours)
    public final static long REQUEST_TIMEOUT = 48*60*60*1000;

    // singleton enforcement
    public static final AntiEntropyService instance = new AntiEntropyService();

    /**
     * Map of column families to remote endpoints that need to rendezvous. The
     * first endpoint to arrive at the rendezvous will store its tree in the
     * appropriate slot of the TreePair object, and the second to arrive will
     * remove the stored tree, and compare it.
     *
     * This map is only accessed from AE_SERVICE_STAGE, so it is not synchronized.
     */
    private final Map<CFPair, ExpiringMap<InetAddress, TreePair>> trees;

    /**
     * Protected constructor. Use AntiEntropyService.instance.
     */
    protected AntiEntropyService()
    {
        trees = new HashMap<CFPair, ExpiringMap<InetAddress, TreePair>>();
    }

    /**
     * Returns the map of waiting rendezvous endpoints to trees for the given cf.
     * Should only be called within AE_SERVICE_STAGE.
     *
     * @param cf Column family to fetch trees for.
     * @return The store of trees for the given cf.
     */
    private ExpiringMap<InetAddress, TreePair> rendezvousPairs(CFPair cf)
    {
        ExpiringMap<InetAddress, TreePair> ctrees = trees.get(cf);
        if (ctrees == null)
        {
            ctrees = new ExpiringMap<InetAddress, TreePair>(REQUEST_TIMEOUT);
            trees.put(cf, ctrees);
        }
        return ctrees;
    }

    /**
     * Return all of the neighbors with whom we share data.
     */
    public static Set<InetAddress> getNeighbors(String table)
    {
        StorageService ss = StorageService.instance;
        Set<InetAddress> neighbors = new HashSet<InetAddress>();
        Map<Range, List<InetAddress>> replicaSets = ss.getRangeToAddressMap(table);
        for (Range range : ss.getLocalRanges(table))
        {
            // for every range stored locally (replica or original) collect neighbors storing copies
            neighbors.addAll(replicaSets.get(range));
        }
        neighbors.remove(FBUtilities.getLocalAddress());
        return neighbors;
    }

    /**
     * Register a tree from the given endpoint to be compared to the appropriate trees
     * in AE_SERVICE_STAGE when they become available.
     *
     * @param cf The column family of the tree.
     * @param endpoint The endpoint which owns the given tree.
     * @param tree The tree for the endpoint.
     */
    private void rendezvous(CFPair cf, InetAddress endpoint, MerkleTree tree)
    {
        InetAddress LOCAL = FBUtilities.getLocalAddress();

        // return the rendezvous pairs for this cf
        ExpiringMap<InetAddress, TreePair> ctrees = rendezvousPairs(cf);

        List<Differencer> differencers = new ArrayList<Differencer>();
        if (LOCAL.equals(endpoint))
        {
            // we're registering a local tree: rendezvous with all remote trees
            for (InetAddress neighbor : getNeighbors(cf.left))
            {
                TreePair waiting = ctrees.remove(neighbor);
                if (waiting != null && waiting.right != null)
                {
                    // the neighbor beat us to the rendezvous: queue differencing
                    differencers.add(new Differencer(cf, LOCAL, neighbor,
                                                     tree, waiting.right));
                    continue;
                }

                // else, the local tree is first to the rendezvous: store and wait
                ctrees.put(neighbor, new TreePair(tree, null));
                logger.debug("Stored local tree for " + cf + " to wait for " + neighbor);
            }
        }
        else
        {
            // we're registering a remote tree: rendezvous with the local tree
            TreePair waiting = ctrees.remove(endpoint);
            if (waiting != null && waiting.left != null)
            {
                // the local tree beat us to the rendezvous: queue differencing
                differencers.add(new Differencer(cf, LOCAL, endpoint,
                                                 waiting.left, tree));
            }
            else
            {
                // else, the remote tree is first to the rendezvous: store and wait
                ctrees.put(endpoint, new TreePair(null, tree));
                logger.debug("Stored remote tree for " + cf + " from " + endpoint);
            }
        }

        for (Differencer differencer : differencers)
        {
            logger.info("Queueing comparison " + differencer);
            StageManager.getStage(StageManager.AE_SERVICE_STAGE).execute(differencer);
        }
    }

    /**
     * Called by a Validator to send a valid tree to endpoints storing
     * replicas of local data.
     *
     * @param validator A locally generated validator.
     * @param local The local endpoint.
     * @param neighbors A list of neighbor endpoints to send the tree to.
     */
    void notifyNeighbors(Validator validator, InetAddress local, Collection<InetAddress> neighbors)
    {
        MessagingService ms = MessagingService.instance;

        try
        {
            Message message = TreeResponseVerbHandler.makeVerb(local, validator);
            logger.info("Sending AEService tree for " + validator.cf + " to: " + neighbors);
            for (InetAddress neighbor : neighbors)
                ms.sendOneWay(message, neighbor);
        }
        catch (Exception e)
        {
            logger.error("Could not send valid tree to endpoints: " + neighbors, e);
        }
    }

    /**
     * Should only be used in AE_SERVICE_STAGE or for testing.
     *
     * @param table Table containing cf.
     * @param cf The column family.
     * @param remote The remote endpoint for the rendezvous.
     * @return The tree pair for the given rendezvous if it exists, else  null.
     */
    TreePair getRendezvousPair_TestsOnly(String table, String cf, InetAddress remote)
    {
        return rendezvousPairs(new CFPair(table, cf)).get(remote);
    }

    /**
     * A Strategy to handle building and validating a merkle tree for a column family.
     *
     * Lifecycle:
     * 1. prepare() - Initialize tree with samples.
     * 2. add() - 0 or more times, to add hashes to the tree.
     * 3. complete() - Enqueues any operations that were blocked waiting for a valid tree.
     */
    public static class Validator implements Callable<Object>
    {
        public final CFPair cf; // TODO keep a CFS reference as a field instead of its string representation
        public final MerkleTree tree;

        // the minimum token sorts first, but falls into the last range
        private transient List<MerkleTree.RowHash> minrows;
        // null when all rows with the min token have been consumed
        private transient Token mintoken;
        private transient long validated;
        private transient MerkleTree.TreeRange range;
        private transient MerkleTree.TreeRangeIterator ranges;

        public final static MerkleTree.RowHash EMPTY_ROW = new MerkleTree.RowHash(null, new byte[0]);
        
        Validator(CFPair cf)
        {
            this(cf,
                 // TODO: memory usage (maxsize) should either be tunable per
                 // CF, globally, or as shared for all CFs in a cluster
                 new MerkleTree(DatabaseDescriptor.getPartitioner(), MerkleTree.RECOMMENDED_DEPTH, (int)Math.pow(2, 15)));
        }

        Validator(CFPair cf, MerkleTree tree)
        {
            assert cf != null && tree != null;
            this.cf = cf;
            this.tree = tree;
            minrows = new ArrayList<MerkleTree.RowHash>();
            mintoken = null;
            validated = 0;
            range = null;
            ranges = null;
        }
        
        public void prepare(ColumnFamilyStore cfs)
        {
            List<DecoratedKey> keys = new ArrayList<DecoratedKey>();
            for (IndexSummary.KeyPosition info: cfs.allIndexPositions())
                keys.add(info.key);

            if (keys.isEmpty())
            {
                // use an even tree distribution
                tree.init();
            }
            else
            {
                int numkeys = keys.size();
                Random random = new Random();
                // sample the column family using random keys from the index 
                while (true)
                {
                    DecoratedKey dk = keys.get(random.nextInt(numkeys));
                    if (!tree.split(dk.token))
                        break;
                }
            }
            logger.debug("Prepared AEService tree of size " + tree.size() + " for " + cf);
            mintoken = tree.partitioner().getMinimumToken();
            ranges = tree.invalids(new Range(mintoken, mintoken));
        }

        /**
         * Called (in order) for every row present in the CF.
         * Hashes the row, and adds it to the tree being built.
         *
         * There are four possible cases:
         *  1. Token is greater than range.right (we haven't generated a range for it yet),
         *  2. Token is less than/equal to range.left (the range was valid),
         *  3. Token is contained in the range (the range is in progress),
         *  4. No more invalid ranges exist.
         *
         * TODO: Because we only validate completely empty trees at the moment, we
         * do not bother dealing with case 2 and case 4 should result in an error.
         *
         * Additionally, there is a special case for the minimum token, because
         * although it sorts first, it is contained in the last possible range.
         *
         * @param row The row.
         */
        public void add(CompactedRow row)
        {
            if (mintoken != null)
            {
                assert ranges != null : "Validator was not prepared()";

                // check for the minimum token special case
                if (row.key.token.compareTo(mintoken) == 0)
                {
                    // and store it to be appended when we complete
                    minrows.add(rowHash(row));
                    return;
                }
                mintoken = null;
            }

            if (range == null)
                range = ranges.next();

            // generate new ranges as long as case 1 is true
            while (!range.contains(row.key.token))
            {
                // add the empty hash, and move to the next range
                range.addHash(EMPTY_ROW);
                range = ranges.next();
            }

            // case 3 must be true: mix in the hashed row
            range.addHash(rowHash(row));
        }

        private MerkleTree.RowHash rowHash(CompactedRow row)
        {
            validated++;
            // MerkleTree uses XOR internally, so we want lots of output bits here            
            MessageDigest messageDigest = FBUtilities.createDigest("SHA-256"); // BIGDATA
            messageDigest.update(row.key.key.getBytes()); // BIGDATA
            messageDigest.update(row.headerBuffer.getData(), 0, row.headerBuffer.getLength()); // BIGDATA
            messageDigest.update(row.buffer.getData(), 0, row.buffer.getLength()); // BIGDATA
            return new MerkleTree.RowHash(row.key.token, messageDigest.digest()); // BIGDATA
        }

        /**
         * Registers the newly created tree for rendezvous in AE_SERVICE_STAGE.
         */
        public void complete()
        {
            assert ranges != null : "Validator was not prepared()";

            if (range != null)
                range.addHash(EMPTY_ROW);
            while (ranges.hasNext())
            {
                range = ranges.next();
                range.addHash(EMPTY_ROW);
            }
            // add rows with the minimum token to the final range
            if (!minrows.isEmpty())
                for (MerkleTree.RowHash minrow : minrows)
                    range.addHash(minrow);

            StageManager.getStage(StageManager.AE_SERVICE_STAGE).submit(this);
            logger.debug("Validated " + validated + " rows into AEService tree for " + cf);
        }
        
        /**
         * Called after the validation lifecycle to trigger additional action
         * with the now valid tree. Runs in AE_SERVICE_STAGE.
         *
         * @return A meaningless object.
         */
        public Object call() throws Exception
        {
            AntiEntropyService aes = AntiEntropyService.instance;
            InetAddress local = FBUtilities.getLocalAddress();

            Collection<InetAddress> neighbors = getNeighbors(cf.left);

            // store the local tree and then broadcast it to our neighbors
            aes.rendezvous(cf, local, tree);
            aes.notifyNeighbors(this, local, neighbors);

            // return any old object
            return AntiEntropyService.class;
        }
    }

    /**
     * Compares two trees, and launches repairs for disagreeing ranges.
     */
    public static class Differencer implements Runnable
    {
        public final CFPair cf;
        public final InetAddress local;
        public final InetAddress remote;
        public final MerkleTree ltree;
        public final MerkleTree rtree;
        public final List<MerkleTree.TreeRange> differences;

        public Differencer(CFPair cf, InetAddress local, InetAddress remote, MerkleTree ltree, MerkleTree rtree)
        {
            this.cf = cf;
            this.local = local;
            this.remote = remote;
            this.ltree = ltree;
            this.rtree = rtree;
            differences = new ArrayList<MerkleTree.TreeRange>();
        }

        /**
         * Compares our trees, and triggers repairs for any ranges that mismatch.
         */
        public void run()
        {
            StorageService ss = StorageService.instance;

            // restore partitioners (in case we were serialized)
            if (ltree.partitioner() == null)
                ltree.partitioner(ss.getPartitioner());
            if (rtree.partitioner() == null)
                rtree.partitioner(ss.getPartitioner());

            // determine the ranges where responsibility overlaps
            Set<Range> interesting = new HashSet(ss.getRangesForEndPoint(cf.left, local));
            interesting.retainAll(ss.getRangesForEndPoint(cf.left, remote));

            // compare trees, and filter out uninteresting differences
            for (MerkleTree.TreeRange diff : MerkleTree.difference(ltree, rtree))
            {
                for (Range localrange: interesting)
                {
                    if (diff.intersects(localrange))
                    {
                        differences.add(diff);
                        break; // the inner loop
                    }
                }
            }
            
            // choose a repair method based on the significance of the difference
            float difference = differenceFraction();
            try
            {
                if (difference == 0.0)
                {
                    logger.debug("Endpoints " + local + " and " + remote + " are consistent for " + cf);
                    return;
                }
                
                performStreamingRepair();
            }
            catch(IOException e)
            {
                throw new RuntimeException(e);
            }
        }
        
        /**
         * @return the fraction of the keyspace that is different, as represented by our
         * list of different ranges. A range at depth 0 == 1.0, at depth 1 == 0.5, etc.
         */
        float differenceFraction()
        {
            double fraction = 0.0;
            for (MerkleTree.TreeRange diff : differences)
                fraction += 1.0 / Math.pow(2, diff.depth);
            return (float)fraction;
        }

        /**
         * Sends our list of differences to the remote endpoint using the
         * Streaming API.
         */
        void performStreamingRepair() throws IOException
        {
            logger.info("Performing streaming repair of " + differences.size() + " ranges to " + remote + " for " + cf);
            ColumnFamilyStore cfstore = Table.open(cf.left).getColumnFamilyStore(cf.right);
            try
            {
                List<Range> ranges = new ArrayList<Range>(differences);
                final List<SSTableReader> sstables = CompactionManager.instance.submitAnticompaction(cfstore, ranges, remote).get();
                Future f = StageManager.getStage(StageManager.STREAM_STAGE).submit(new WrappedRunnable() 
                {
                    protected void runMayThrow() throws Exception
                    {
                        StreamOut.transferSSTables(remote, sstables, cf.left);
                        StreamOutManager.remove(remote);
                    }
                });
                f.get();
            }
            catch(Exception e)
            {
                throw new IOException("Streaming repair failed.", e);
            }
            logger.info("Finished streaming repair to " + remote + " for " + cf);
        }

        public String toString()
        {
            return "#<Differencer " + cf + " local=" + local + " remote=" + remote + ">";
        }
    }

    /**
     * Handler for requests from remote nodes to generate a valid tree.
     * The payload is a CFPair representing the columnfamily to validate.
     */
    public static class TreeRequestVerbHandler implements IVerbHandler, ICompactSerializer<CFPair>
    {
        public static final TreeRequestVerbHandler SERIALIZER = new TreeRequestVerbHandler();
        static Message makeVerb(String table, String cf)
        {
            try
            {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(bos);
                SERIALIZER.serialize(new CFPair(table, cf), dos);
                return new Message(FBUtilities.getLocalAddress(), StageManager.AE_SERVICE_STAGE, StorageService.Verb.TREE_REQUEST, bos.toByteArray());
            }
            catch(IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        public void serialize(CFPair treerequest, DataOutputStream dos) throws IOException
        {
            dos.writeUTF(treerequest.left);
            dos.writeUTF(treerequest.right);
        }

        public CFPair deserialize(DataInputStream dis) throws IOException
        {
            return new CFPair(dis.readUTF(), dis.readUTF());
        }

        /**
         * Trigger a validation compaction which will return the tree upon completion.
         */
        public void doVerb(Message message)
        { 
            byte[] bytes = message.getMessageBody();
            
            ByteArrayInputStream buffer = new ByteArrayInputStream(bytes);
            try
            {
                CFPair cf = this.deserialize(new DataInputStream(buffer));

                // trigger readonly-compaction
                logger.debug("Queueing validation compaction for " + cf + ", " + message.getFrom());
                ColumnFamilyStore store = Table.open(cf.left).getColumnFamilyStore(cf.right);
                Validator validator = new Validator(cf);
                CompactionManager.instance.submitValidation(store, validator);
            }
            catch (IOException e)
            {
                throw new IOError(e);            
            }
        }
    }

    /**
     * Handler for responses from remote nodes which contain a valid tree.
     * The payload is a completed Validator object from the remote endpoint.
     */
    public static class TreeResponseVerbHandler implements IVerbHandler, ICompactSerializer<Validator>
    {
        public static final TreeResponseVerbHandler SERIALIZER = new TreeResponseVerbHandler();
        static Message makeVerb(InetAddress local, Validator validator)
        {
            try
            {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(bos);
                SERIALIZER.serialize(validator, dos);
                return new Message(local, StageManager.AE_SERVICE_STAGE, StorageService.Verb.TREE_RESPONSE, bos.toByteArray());
            }
            catch(IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        public void serialize(Validator v, DataOutputStream dos) throws IOException
        {
            TreeRequestVerbHandler.SERIALIZER.serialize(v.cf, dos);
            ObjectOutputStream oos = new ObjectOutputStream(dos);
            oos.writeObject(v.tree);
            oos.flush();
        }

        public Validator deserialize(DataInputStream dis) throws IOException
        {
            final CFPair cf = TreeRequestVerbHandler.SERIALIZER.deserialize(dis);
            ObjectInputStream ois = new ObjectInputStream(dis);
            try
            {
                return new Validator(cf, (MerkleTree)ois.readObject());
            }
            catch(Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        public void doVerb(Message message)
        { 
            byte[] bytes = message.getMessageBody();
            ByteArrayInputStream buffer = new ByteArrayInputStream(bytes);

            try
            {
                // deserialize the remote tree, and register it
                Validator rvalidator = this.deserialize(new DataInputStream(buffer));
                AntiEntropyService.instance.rendezvous(rvalidator.cf, message.getFrom(), rvalidator.tree);
            }
            catch (IOException e)
            {
                throw new IOError(e);
            }
        }
    }

    /**
     * A tuple of table and cf.
     */
    static class CFPair extends Pair<String,String>
    {
        public CFPair(String table, String cf)
        {
            super(table, cf);
            assert table != null && cf != null;
        }
    }

    /**
     * A tuple of a local and remote tree. One of the trees should be null, but
     * not both.
     */
    static class TreePair extends Pair<MerkleTree,MerkleTree>
    {
        public TreePair(MerkleTree local, MerkleTree remote)
        {
            super(local, remote);
            assert local != null ^ remote != null;
        }
    }
}