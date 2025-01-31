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

import com.graphhopper.coll.GHIntObjectHashMap;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.BeelineWeightApproximator;
import com.graphhopper.routing.weighting.WeightApproximator;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.SPTEntry;
import com.graphhopper.util.*;

import java.util.PriorityQueue;

/**
 * This class implements the A* algorithm according to
 * http://en.wikipedia.org/wiki/A*_search_algorithm
 * <p>
 * Different distance calculations can be used via setApproximation.
 * <p>
 *
 * @author Peter Karich
 */
public class AStar extends AbstractRoutingAlgorithm {
    protected WeightApproximator weightApprox;
    protected int visitedCount;
    protected GHIntObjectHashMap<AStarEntry> fromMap;
    protected PriorityQueue<AStarEntry> prioQueueOpenSet;
    protected AStarEntry currEdge;
    protected int to = -1;

    public AStar(Graph graph, Weighting weighting, TraversalMode tMode) {
        super(graph, weighting, tMode);
        int size = Math.min(Math.max(200, graph.getNodes() / 10), 2000);
        initCollections(size);
        BeelineWeightApproximator defaultApprox = new BeelineWeightApproximator(nodeAccess, weighting);
        defaultApprox.setDistanceCalc(Helper.DIST_PLANE);
        setApproximation(defaultApprox);
    }

    /**
     * @param approx defines how distance to goal Node is approximated
     */
    public AStar setApproximation(WeightApproximator approx) {
        weightApprox = approx;
        return this;
    }

    protected void initCollections(int size) {
        fromMap = new GHIntObjectHashMap<>();
        prioQueueOpenSet = new PriorityQueue<>(size);
    }

    @Override
    public Path calcPath(int from, int to) {
        checkAlreadyRun();
        this.to = to;
        weightApprox.setTo(to);
        double weightToGoal = weightApprox.approximate(from);
        currEdge = new AStarEntry(EdgeIterator.NO_EDGE, from, 0 + weightToGoal, 0);
        if (!traversalMode.isEdgeBased()) {
            fromMap.put(from, currEdge);
        }
        return runAlgo();
    }

    private Path runAlgo() {
        double currWeightToGoal, estimationFullWeight;
        EdgeExplorer explorer = outEdgeExplorer;
        while (true) {
            int currVertex = currEdge.adjNode;
            visitedCount++;
            if (isMaxVisitedNodesExceeded())
                return createEmptyPath();

            if (finished())
                break;

            EdgeIterator iter = explorer.setBaseNode(currVertex);
            while (iter.next()) {
                if (!accept(iter, currEdge.edge))
                    continue;

                double alreadyVisitedWeight = weighting.calcWeight(iter, false, currEdge.edge)
                        + currEdge.weightOfVisitedPath;
                if (Double.isInfinite(alreadyVisitedWeight))
                    continue;

                int traversalId = traversalMode.createTraversalId(iter, false);
                AStarEntry ase = fromMap.get(traversalId);
                if (ase == null || ase.weightOfVisitedPath > alreadyVisitedWeight) {
                    int neighborNode = iter.getAdjNode();
                    currWeightToGoal = weightApprox.approximate(neighborNode);
                    estimationFullWeight = alreadyVisitedWeight + currWeightToGoal;
                    if (ase == null) {
                        ase = new AStarEntry(iter.getEdge(), neighborNode, estimationFullWeight, alreadyVisitedWeight);
                        fromMap.put(traversalId, ase);
                    } else {
//                        assert (ase.weight > 0.9999999 * estimationFullWeight) : "Inconsistent distance estimate. It is expected weight >= estimationFullWeight but was "
//                                + ase.weight + " < " + estimationFullWeight + " (" + ase.weight / estimationFullWeight + "), and weightOfVisitedPath:"
//                                + ase.weightOfVisitedPath + " vs. alreadyVisitedWeight:" + alreadyVisitedWeight + " (" + ase.weightOfVisitedPath / alreadyVisitedWeight + ")";

                        prioQueueOpenSet.remove(ase);
                        ase.edge = iter.getEdge();
                        ase.weight = estimationFullWeight;
                        ase.weightOfVisitedPath = alreadyVisitedWeight;
                    }

                    ase.parent = currEdge;
                    prioQueueOpenSet.add(ase);

                    updateBestPath(iter, ase, traversalId);
                }
            }

            if (prioQueueOpenSet.isEmpty())
                return createEmptyPath();

            currEdge = prioQueueOpenSet.poll();
            if (currEdge == null)
                throw new AssertionError("Empty edge cannot happen");
        }

        return extractPath();
    }

    @Override
    protected Path extractPath() {
        return new Path(graph, weighting).
                setWeight(currEdge.weight).setSPTEntry(currEdge).extract();
    }

    @Override
    protected boolean finished() {
        return currEdge.adjNode == to;
    }

    @Override
    public int getVisitedNodes() {
        return visitedCount;
    }

    protected void updateBestPath(EdgeIteratorState edgeState, SPTEntry bestSPTEntry, int traversalId) {
    }

    public static class AStarEntry extends SPTEntry {
        double weightOfVisitedPath;

        public AStarEntry(int edgeId, int adjNode, double weightForHeap, double weightOfVisitedPath) {
            super(edgeId, adjNode, weightForHeap);
            this.weightOfVisitedPath = weightOfVisitedPath;
        }

        @Override
        public final double getWeightOfVisitedPath() {
            return weightOfVisitedPath;
        }

        @Override
        public AStarEntry getParent() {
            return (AStarEntry) parent;
        }
    }

    @Override
    public String getName() {
        return Parameters.Algorithms.ASTAR + "|" + weightApprox;
    }
}
