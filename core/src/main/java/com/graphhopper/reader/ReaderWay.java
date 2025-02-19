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
package com.graphhopper.reader;

import com.carrotsearch.hppc.LongArrayList;

/**
 * Represents a way received from the reader.
 * <p>
 *
 * @author Nop
 */
public class ReaderWay extends ReaderElement {
    // ORS-GH MOD START
    // ORG CODE
    /*protected final LongArrayList nodes = new LongArrayList(5);
    public ReaderWay(long id) {
        super(id, WAY);
    }*/
    // ORG CODE END

    protected final LongArrayList nodes;

    public ReaderWay(long id) {
        this(id, 5);
    }

    public ReaderWay(long id, int size) {
        super(id, WAY);
        nodes = new LongArrayList(size);
    }
    // ORS-GH MOD END

    public LongArrayList getNodes() {
        return nodes;
    }

    @Override
    public String toString() {
        return "Way id:" + getId() + ", nodes:" + nodes.size() + ", tags:" + super.toString();
    }
}
