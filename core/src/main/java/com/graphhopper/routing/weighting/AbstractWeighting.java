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
package com.graphhopper.routing.weighting;

import com.graphhopper.routing.profiles.BooleanEncodedValue;
import com.graphhopper.routing.profiles.DecimalEncodedValue;
import com.graphhopper.routing.util.DefaultSpeedCalculator;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.HintsMap;
import com.graphhopper.routing.util.SpeedCalculator;
import com.graphhopper.util.EdgeIteratorState;

import static com.graphhopper.util.Helper.toLowerCase;

/**
 * @author Peter Karich
 */
public abstract class AbstractWeighting implements Weighting {
    protected final FlagEncoder flagEncoder;
    protected final DecimalEncodedValue avSpeedEnc;
    protected final BooleanEncodedValue accessEnc;
    protected SpeedCalculator speedCalculator;

    protected AbstractWeighting(FlagEncoder encoder) {
        this.flagEncoder = encoder;
        if (!flagEncoder.isRegistered())
            throw new IllegalStateException("Make sure you add the FlagEncoder " + flagEncoder + " to an EncodingManager before using it elsewhere");
        if (!isValidName(getName()))
            throw new IllegalStateException("Not a valid name for a Weighting: " + getName());

        avSpeedEnc = encoder.getAverageSpeedEnc();
        accessEnc = encoder.getAccessEnc();
        speedCalculator = new DefaultSpeedCalculator(encoder);
    }

    @Override
    public double calcWeight(EdgeIteratorState edge, boolean reverse, int prevOrNextEdgeId, long edgeEnterTime) {
        return calcWeight(edge, reverse, prevOrNextEdgeId);
    }

    @Override
    public long calcMillis(EdgeIteratorState edgeState, boolean reverse, int prevOrNextEdgeId) {
        return calcMillis(edgeState, reverse, prevOrNextEdgeId, -1);
    }

    @Override
    public long calcMillis(EdgeIteratorState edgeState, boolean reverse, int prevOrNextEdgeId, long edgeEnterTime) {
        // special case for loop edges: since they do not have a meaningful direction we always need to read them in
        // forward direction
        if (edgeState.getBaseNode() == edgeState.getAdjNode()) {
            reverse = false;
        }

        if (reverse && !edgeState.getReverse(accessEnc) || !reverse && !edgeState.get(accessEnc))
            throw new IllegalStateException("Calculating time should not require to read speed from edge in wrong direction. " +
                    "(" + edgeState.getBaseNode() + " - " + edgeState.getAdjNode() + ") "
                    + edgeState.fetchWayGeometry(3) + ", dist: " + edgeState.getDistance() + " "
                    + "Reverse:" + reverse + ", fwd:" + edgeState.get(accessEnc) + ", bwd:" + edgeState.getReverse(accessEnc) + ", fwd-speed: " + edgeState.get(avSpeedEnc) + ", bwd-speed: " + edgeState.getReverse(avSpeedEnc));

        double speed = speedCalculator.getSpeed(edgeState, reverse, edgeEnterTime);
        if (Double.isInfinite(speed) || Double.isNaN(speed) || speed < 0)
            throw new IllegalStateException("Invalid speed stored in edge! " + speed);
        if (speed == 0)
            throw new IllegalStateException("Speed cannot be 0 for unblocked edge, use access properties to mark edge blocked! Should only occur for shortest path calculation. See #242.");

        return (long) (edgeState.getDistance() * 3600 / speed);
    }

    @Override
    public boolean matches(HintsMap reqMap) {
        return getName().equals(reqMap.getWeighting()) && flagEncoder.toString().equals(reqMap.getVehicle());
    }

    @Override
    public FlagEncoder getFlagEncoder() {
        return flagEncoder;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 71 * hash + toString().hashCode();
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        final Weighting other = (Weighting) obj;
        return toString().equals(other.toString());
    }

    static final boolean isValidName(String name) {
        if (name == null || name.isEmpty())
            return false;

        return name.matches("[\\|_a-z]+");
    }

    /**
     * Replaces all characters which are not numbers, characters or underscores with underscores
     */
    public static String weightingToFileName(Weighting w) {
        return toLowerCase(w.toString()).replaceAll("\\|", "_");
    }

    @Override
    public String toString() {
        return getName() + "|" + flagEncoder;
    }

    @Override
    public boolean isTimeDependent() {
        return false;
    }

    @Override
    public SpeedCalculator getSpeedCalculator() {
        return speedCalculator;
    }

    @Override
    public void setSpeedCalculator(SpeedCalculator speedCalculator) {
        this.speedCalculator = speedCalculator;
    }
}
