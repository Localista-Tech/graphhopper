/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing;

import com.carrotsearch.hppc.IntObjectMap;
import com.graphhopper.coll.GHIntObjectHashMap;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.TurnWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.SPTEntry;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.GHUtility;

import java.util.PriorityQueue;

import static com.graphhopper.util.EdgeIterator.ANY_EDGE;

/**
 * Common subclass for bidirectional algorithms.
 * <p>
 *
 * @author Peter Karich
 * @author easbar
 */
public abstract class AbstractBidirAlgo extends AbstractRoutingAlgorithm {
    protected int from;
    protected int to;
    protected int fromOutEdge;
    protected int toInEdge;
    protected IntObjectMap<SPTEntry> bestWeightMapFrom;
    protected IntObjectMap<SPTEntry> bestWeightMapTo;
    protected IntObjectMap<SPTEntry> bestWeightMapOther;
    protected SPTEntry currFrom;
    protected SPTEntry currTo;
    protected PathBidirRef bestPath;
    PriorityQueue<SPTEntry> pqOpenSetFrom;
    PriorityQueue<SPTEntry> pqOpenSetTo;
    private boolean updateBestPath = true;
    protected boolean finishedFrom;
    protected boolean finishedTo;
    int visitedCountFrom;
    int visitedCountTo;
    // ORS-GH MOD START
    // Modification by Andrzej Oles: ALT patch https://github.com/GIScience/graphhopper/issues/21
    protected double approximatorOffset = 0.0;
    // ORS-GH MOD END



    public AbstractBidirAlgo(Graph graph, Weighting weighting, TraversalMode tMode) {
        super(graph, weighting, tMode);
        fromOutEdge = ANY_EDGE;
        toInEdge = ANY_EDGE;
        int size = Math.min(Math.max(200, graph.getNodes() / 10), 150_000);
        initCollections(size);
    }

    protected void initCollections(int size) {
        pqOpenSetFrom = new PriorityQueue<>(size);
        bestWeightMapFrom = new GHIntObjectHashMap<>(size);

        pqOpenSetTo = new PriorityQueue<>(size);
        bestWeightMapTo = new GHIntObjectHashMap<>(size);
    }

    /**
     * Creates the root shortest path tree entry for the forward or backward search.
     */
    protected abstract SPTEntry createStartEntry(int node, double weight, boolean reverse);

    /**
     * Creates a new entry of the shortest path tree (a {@link SPTEntry} or one of its subclasses) during a dijkstra
     * expansion.
     *
     * @param edge    the edge that is currently processed for the expansion
     * @param incEdge the id of the edge that is incoming to the node the edge is pointed at. usually this is the same as
     *                edge.getEdge(), but for edge-based CH and in case edge is a shortcut incEdge is the original edge
     *                that is incoming to the node
     * @param weight  the weight the shortest path three entry should carry
     * @param parent  the parent entry of in the shortest path tree
     * @param reverse true if we are currently looking at the backward search, false otherwise
     */
    protected abstract SPTEntry createEntry(EdgeIteratorState edge, int incEdge, double weight, SPTEntry parent, boolean reverse);

    @Override
    public Path calcPath(int from, int to) {
        return calcPath(from, to, ANY_EDGE, ANY_EDGE);
    }

    /**
     * like {@link #calcPath(int, int)}, but this method also allows to strictly restrict the edge the
     * path will begin with and the edge it will end with.
     *
     * @param fromOutEdge the edge id of the first edge of the path. using {@link EdgeIterator#ANY_EDGE} means
     *                    not enforcing the first edge of the path
     * @param toInEdge    the edge id of the last edge of the path. using {@link EdgeIterator#ANY_EDGE} means
     *                    not enforcing the last edge of the path
     */
    public Path calcPath(int from, int to, int fromOutEdge, int toInEdge) {
        if ((fromOutEdge != ANY_EDGE || toInEdge != ANY_EDGE) && !traversalMode.isEdgeBased()) {
            throw new IllegalArgumentException("Restricting the start/target edges is only possible for edge-based graph traversal");
        }
        this.fromOutEdge = fromOutEdge;
        this.toInEdge = toInEdge;
        checkAlreadyRun();
        createAndInitPath();
        init(from, 0, to, 0);
        runAlgo();
        return extractPath();
    }

    protected Path createAndInitPath() {
        bestPath = new PathBidirRef(graph, weighting);
        return bestPath;
    }

    void init(int from, double fromWeight, int to, double toWeight) {
        initFrom(from, fromWeight);
        initTo(to, toWeight);
        postInit(from, to);
    }

    protected void initFrom(int from, double weight) {
        this.from = from;
        currFrom = createStartEntry(from, weight, false);
        pqOpenSetFrom.add(currFrom);
        if (!traversalMode.isEdgeBased()) {
            bestWeightMapFrom.put(from, currFrom);
        }
    }

    protected void initTo(int to, double weight) {
        this.to = to;
        currTo = createStartEntry(to, weight, true);
        pqOpenSetTo.add(currTo);
        if (!traversalMode.isEdgeBased()) {
            bestWeightMapTo.put(to, currTo);
        }
    }

    protected void postInit(int from, int to) {
        if (!traversalMode.isEdgeBased()) {
            if (updateBestPath) {
                bestWeightMapOther = bestWeightMapFrom;
                updateBestPath(GHUtility.getEdge(graph, currFrom.adjNode, to), currFrom, to, true);
            }
        } else if (from == to && fromOutEdge == ANY_EDGE && toInEdge == ANY_EDGE) {
            // special handling if start and end are the same and no directions are restricted
            // the resulting weight should be zero
            if (currFrom.weight != 0 || currTo.weight != 0) {
                throw new IllegalStateException("If from=to, the starting weight must be zero for from and to");
            }
            bestPath.sptEntry = currFrom;
            bestPath.edgeTo = currTo;
            bestPath.setWeight(0);
            finishedFrom = true;
            finishedTo = true;
            return;
        }
        postInitFrom();
        postInitTo();
    }

    protected void postInitFrom() {
        if (fromOutEdge == ANY_EDGE) {
            fillEdgesFromUsingFilter(additionalEdgeFilter);
        } else {
            // need to use a local reference here, because additionalEdgeFilter is modified when calling fillEdgesFromUsingFilter
            final EdgeFilter tmpFilter = additionalEdgeFilter;
            fillEdgesFromUsingFilter(new EdgeFilter() {
                @Override
                public boolean accept(EdgeIteratorState edgeState) {
                    return (tmpFilter == null || tmpFilter.accept(edgeState)) && edgeState.getOrigEdgeFirst() == fromOutEdge;
                }
            });
        }
    }

    protected void postInitTo() {
        if (toInEdge == ANY_EDGE) {
            fillEdgesToUsingFilter(additionalEdgeFilter);
        } else {
            final EdgeFilter tmpFilter = additionalEdgeFilter;
            fillEdgesToUsingFilter(new EdgeFilter() {
                @Override
                public boolean accept(EdgeIteratorState edgeState) {
                    return (tmpFilter == null || tmpFilter.accept(edgeState)) && edgeState.getOrigEdgeLast() == toInEdge;
                }
            });
        }
    }

    /**
     * @param edgeFilter edge filter used to fill edges. the {@link #additionalEdgeFilter} reference will be set to
     *                   edgeFilter by this method, so make sure edgeFilter does not use it directly.
     */
    protected void fillEdgesFromUsingFilter(EdgeFilter edgeFilter) {
        // we temporarily ignore the additionalEdgeFilter
        EdgeFilter tmpFilter = additionalEdgeFilter;
        additionalEdgeFilter = edgeFilter;
        finishedFrom = !fillEdgesFrom();
        additionalEdgeFilter = tmpFilter;
    }

    /**
     * @see #fillEdgesFromUsingFilter(EdgeFilter)
     */
    protected void fillEdgesToUsingFilter(EdgeFilter edgeFilter) {
        // we temporarily ignore the additionalEdgeFilter
        EdgeFilter tmpFilter = additionalEdgeFilter;
        additionalEdgeFilter = edgeFilter;
        finishedTo = !fillEdgesTo();
        additionalEdgeFilter = tmpFilter;
    }

    protected void runAlgo() {
        while (!finished() && !isMaxVisitedNodesExceeded()) {
            if (!finishedFrom)
                finishedFrom = !fillEdgesFrom();

            if (!finishedTo)
                finishedTo = !fillEdgesTo();
        }
    }

    // http://www.cs.princeton.edu/courses/archive/spr06/cos423/Handouts/EPP%20shortest%20path%20algorithms.pdf
    // a node from overlap may not be on the best path!
    // => when scanning an arc (v, w) in the forward search and w is scanned in the reverseOrder
    //    search, update extractPath = μ if df (v) + (v, w) + dr (w) < μ
    @Override
    protected boolean finished() {
        if (finishedFrom || finishedTo)
            return true;

        // ORS-GH MOD START
        // Modification by Andrzej Oles: ALT patch https://github.com/GIScience/graphhopper/issues/21
        //return currFrom.weight + currTo.weight >= bestPath.getWeight();
        return currFrom.weight + currTo.weight - approximatorOffset >= bestPath.getWeight();
        // ORS-GH MOD END
    }

    boolean fillEdgesFrom() {
        if (pqOpenSetFrom.isEmpty()) {
            return false;
        }
        currFrom = pqOpenSetFrom.poll();
        visitedCountFrom++;
        if (fromEntryCanBeSkipped()) {
            return true;
        }
        if (fwdSearchCanBeStopped()) {
            return false;
        }
        bestWeightMapOther = bestWeightMapTo;
        fillEdges(currFrom, pqOpenSetFrom, bestWeightMapFrom, outEdgeExplorer, false);
        return true;
    }

    boolean fillEdgesTo() {
        if (pqOpenSetTo.isEmpty()) {
            return false;
        }
        currTo = pqOpenSetTo.poll();
        visitedCountTo++;
        if (toEntryCanBeSkipped()) {
            return true;
        }
        if (bwdSearchCanBeStopped()) {
            return false;
        }
        bestWeightMapOther = bestWeightMapFrom;
        fillEdges(currTo, pqOpenSetTo, bestWeightMapTo, inEdgeExplorer, true);
        return true;
    }

    private void fillEdges(SPTEntry currEdge, PriorityQueue<SPTEntry> prioQueue,
                           IntObjectMap<SPTEntry> bestWeightMap, EdgeExplorer explorer, boolean reverse) {
        EdgeIterator iter = explorer.setBaseNode(currEdge.adjNode);
        while (iter.next()) {
            if (!accept(iter, currEdge, reverse))
                continue;

            final double weight = calcWeight(iter, currEdge, reverse);
            if (Double.isInfinite(weight)) {
                continue;
            }
            final int origEdgeId = getOrigEdgeId(iter, reverse);
            final int traversalId = getTraversalId(iter, origEdgeId, reverse);
            SPTEntry entry = bestWeightMap.get(traversalId);
            if (entry == null) {
                entry = createEntry(iter, origEdgeId, weight, currEdge, reverse);
                // ORS-GH MOD START
                // store actual edge ID for use by getIncomingEdge
                entry.originalEdge = EdgeIteratorStateHelper.getOriginalEdge(iter);
                // ORS-GH MOD END
                bestWeightMap.put(traversalId, entry);
                prioQueue.add(entry);
            } else if (entry.getWeightOfVisitedPath() > weight) {
                prioQueue.remove(entry);
                updateEntry(entry, iter, origEdgeId, weight, currEdge, reverse);
                prioQueue.add(entry);
            } else
                continue;

            if (updateBestPath)
                updateBestPath(iter, entry, traversalId, reverse);
        }
    }

    protected void updateBestPath(EdgeIteratorState edgeState, SPTEntry entry, int traversalId, boolean reverse) {
        SPTEntry entryOther = bestWeightMapOther.get(traversalId);
        if (entryOther == null)
            return;

        // update μ
        double weight = entry.getWeightOfVisitedPath() + entryOther.getWeightOfVisitedPath();
        if (traversalMode.isEdgeBased()) {
            if (getIncomingEdge(entryOther) != getIncomingEdge(entry))
                throw new IllegalStateException("cannot happen for edge based execution of " + getName());

            // prevents the path to contain the edge at the meeting point twice and subtracts the weight (excluding turn weight => no previous edge)
            entry = entry.getParent();
            weight -= weighting.calcWeight(edgeState, reverse, EdgeIterator.NO_EDGE);
        }

        if (weight < bestPath.getWeight()) {
            bestPath.setSwitchToFrom(reverse);
            bestPath.setSPTEntry(entry);
            bestPath.setSPTEntryTo(entryOther);
            bestPath.setWeight(weight);
        }
    }

    protected void updateEntry(SPTEntry entry, EdgeIteratorState edge, int edgeId, double weight, SPTEntry parent, boolean reverse) {
        entry.edge = edge.getEdge();
        entry.weight = weight;
        entry.parent = parent;
    }

    protected boolean accept(EdgeIteratorState edge, SPTEntry currEdge, boolean reverse) {
        return accept(edge, getIncomingEdge(currEdge));
    }

    protected int getOrigEdgeId(EdgeIteratorState edge, boolean reverse) {
        return edge.getEdge();
    }

    protected int getIncomingEdge(SPTEntry entry) {
        // ORS-GH MOD START
        // use actual edge ID instead of virtual edge ID (passed to TurnWeighting as prevOrNextEdgeId)
        if (weighting instanceof TurnWeighting && ((TurnWeighting) weighting).inORS)
            return entry.originalEdge;
        return entry.edge;
        // ORS-GH MOD END
    }

    protected int getTraversalId(EdgeIteratorState edge, int origEdgeId, boolean reverse) {
        return traversalMode.createTraversalId(edge, reverse);
    }

    protected double calcWeight(EdgeIteratorState iter, SPTEntry currEdge, boolean reverse) {
        return weighting.calcWeight(iter, reverse, getIncomingEdge(currEdge)) + currEdge.getWeightOfVisitedPath();
    }

    @Override
    protected Path extractPath() {
        if (finished())
            return bestPath.extract();

        return bestPath;
    }

    protected boolean fromEntryCanBeSkipped() {
        return false;
    }

    protected boolean fwdSearchCanBeStopped() {
        return false;
    }

    protected boolean toEntryCanBeSkipped() {
        return false;
    }

    protected boolean bwdSearchCanBeStopped() {
        return false;
    }

    protected double getCurrentFromWeight() {
        return currFrom.weight;
    }

    protected double getCurrentToWeight() {
        return currTo.weight;
    }

    IntObjectMap<SPTEntry> getBestFromMap() {
        return bestWeightMapFrom;
    }

    IntObjectMap<SPTEntry> getBestToMap() {
        return bestWeightMapTo;
    }

    void setBestOtherMap(IntObjectMap<SPTEntry> other) {
        bestWeightMapOther = other;
    }

    protected void setUpdateBestPath(boolean b) {
        updateBestPath = b;
    }

    void setBestPath(PathBidirRef bestPath) {
        this.bestPath = bestPath;
    }

    @Override
    public int getVisitedNodes() {
        return visitedCountFrom + visitedCountTo;
    }

    void setFromDataStructures(AbstractBidirAlgo other) {
        from = other.from;
        fromOutEdge = other.fromOutEdge;
        pqOpenSetFrom = other.pqOpenSetFrom;
        bestWeightMapFrom = other.bestWeightMapFrom;
        finishedFrom = other.finishedFrom;
        currFrom = other.currFrom;
        visitedCountFrom = other.visitedCountFrom;
        // outEdgeExplorer
    }

    void setToDataStructures(AbstractBidirAlgo other) {
        to = other.to;
        toInEdge = other.toInEdge;
        pqOpenSetTo = other.pqOpenSetTo;
        bestWeightMapTo = other.bestWeightMapTo;
        finishedTo = other.finishedTo;
        currTo = other.currTo;
        visitedCountTo = other.visitedCountTo;
        // inEdgeExplorer
    }
}
