/*
 * (C) Copyright 2015 ETH Zurich Systems Group (http://www.systems.ethz.ch/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Markus Pilman <mpilman@inf.ethz.ch>
 *     Simon Loesing <sloesing@inf.ethz.ch>
 *     Thomas Etter <etterth@gmail.com>
 *     Kevin Bocksrocker <kevin.bocksrocker@gmail.com>
 *     Lucas Braun <braunl@inf.ethz.ch>
 */
package ch.ethz.tell;

import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

public class ScanQuery implements Serializable {

    private static final long serialVersionUID = 7526472295622776147L;

    public enum CmpType {
        EQUAL           ((byte)1),
        NOT_EQUAL       ((byte)2),
        LESS            ((byte)3),
        LESS_EQUAL      ((byte)4),
        GREATER         ((byte)5),
        GREATER_EQUAL   ((byte)6),
        LIKE            ((byte)7),
        NOT_LIKE        ((byte)8),
        IS_NULL         ((byte)9),
        IS_NOT_NULL     ((byte)10);
        private byte value;

        CmpType(byte value) {
            this.value = value;
        }

        public final byte toUnderlying() {
            return value;
        }

    }

    public enum QueryType {
        FULL            ((byte)1),
        PROJECTION      ((byte)2),
        AGGREGATIOIN    ((byte)3);
        private byte value;

        QueryType(byte value) {
            this.value = value;
        }

        public final byte toUnderlying() {
            return this.value;
        }
    }

    private class Predicate implements Serializable {
        private static final long serialVersionUID = 7526472295622776144L;

        public CmpType type;
        public short field;
        public PredicateType value;
    }

    public class CNFCLause implements Serializable {
        private static final long serialVersionUID = 7526472295622776140L;

        private ArrayList<Predicate> predicates;

        public CNFCLause() {
            this.predicates = new ArrayList<>();
        }

        public final void addPredicate(CmpType type, short field, PredicateType value) {
            Predicate p = new Predicate();
            p.type = type;
            p.field = field;
            p.value = value;
            predicates.add(p);
        }

        public final int numPredicates() {
            return predicates.size();
        }

        public final CmpType type(int idx) {
            return predicates.get(idx).type;
        }

        public final short field(int idx) {
            return predicates.get(idx).field;
        }

        public final PredicateType value(int idx) {
            return predicates.get(idx).value;
        }

        public final Predicate get(int idx) {
            return predicates.get(idx);
        }
    }

    private int partitionKey;
    private int partitionValue;
    private ArrayList<CNFCLause> selections;
    private ArrayList<Integer> projections;

    public ScanQuery() {
        this(0, 0);
    }

    public ScanQuery(int partitionKey, int partitionValue) {
        this.partitionKey = partitionKey;
        this.partitionValue = partitionValue;
        this.selections = new ArrayList<>();
        this.projections = new ArrayList<>();
    }

    public void addSelection(CNFCLause clause) {
        selections.add(clause);
    }

    public void addProjection(int field) {
        projections.add(field);
    }

    private TreeMap<Short, ArrayList<Pair<Predicate, Byte>>> prepareSerialization() {
        TreeMap<Short, ArrayList<Pair<Predicate, Byte>>> res = new TreeMap<>();
        byte pos = 0;
        for (CNFCLause selection : selections) {
            int predicates = selection.numPredicates();
            for (int i = 0; i < predicates; ++i) {
                short cId = selection.field(i);
                ArrayList<Pair<Predicate, Byte>> list;
                if (res.containsKey(cId)) {
                    list = res.get(cId);
                } else {
                    list = new ArrayList<>();
                    res.put(cId, list);
                }
                Pair<Predicate, Byte> p = new Pair<>(selection.get(i), pos);
                list.add(p);
            }
            ++pos;
        }
        return res;
    }

    private final long size(TreeMap<Short, ArrayList<Pair<Predicate, Byte>>> map) {
        long res = 16 + 8*map.size();
        for (Map.Entry<Short, ArrayList<Pair<Predicate, Byte>>> e : map.entrySet()) {
            for (Pair<Predicate, Byte> p : e.getValue()) {
                res += 8;
                PredicateType value = p.first.value;
                switch (value.getType()) {
                    // Fields smaller than 64 bit can be stored inside the 8 byte allocated for the predicate
                    case Bool:
                    case Short:
                    case Int:
                    case Float:
                        break;
                    // 64 bit fields need another 8 byte in the predicate
                    case Long:
                    case Double:
                        res += 8;
                        break;
                    // Variable sized fields store the size in the predicate followed by the 8 byte padded data
                    case String:
                        String s = value.value();
                        res += s.getBytes(Charset.forName("UTF-8")).length;
                        res += (res % 8 == 0 ? 0 : 8 - (res % 8));
                        break;
                    case ByteArray:
                        byte[] v = value.value();
                        res += v.length;
                        res += (res % 8 == 0 ? 0 : 8 - (res % 8));
                        break;
                }
            }
        }
        return res;
    }

    /**
     * See tellstore/util/scanQueryBatchProcessor.hpp for a description of the memory
     * format of a scan query.
     *
     * @returns pair(size, address)
     */
    final Pair<Long, Long> serialize() {
        TreeMap<Short, ArrayList<Pair<Predicate, Byte>>> map = prepareSerialization();
        sun.misc.Unsafe unsafe = Unsafe.getUnsafe();
        long size = this.size(map);
        long res = unsafe.allocateMemory(size);
        unsafe.putLong(res, map.size());
        unsafe.putInt(res + 8, partitionKey);
        unsafe.putInt(res + 12, partitionValue);
        long offset = 16;
        try {
            for (Map.Entry<Short, ArrayList<Pair<Predicate, Byte>>> e : map.entrySet()) {
                // Column Id
                unsafe.putShort(res + offset, e.getKey());
                offset += 2;
                // Number of predicates for this column
                short numPreds = (short) e.getValue().size();
                unsafe.putShort(res + offset, numPreds);
                offset += 6; // padding
                for (Pair<Predicate, Byte> p : e.getValue()) {
                    unsafe.putByte(res + offset, p.first.type.toUnderlying());
                    offset += 1;
                    unsafe.putByte(res + offset, p.second);
                    offset += 1;
                    PredicateType data = p.first.value;
                    byte[] arr = null;
                    switch (data.getType()) {
                        case Bool:
                            boolean v = data.value();
                            unsafe.putShort(res + offset, (short) (v ? 1 : 0));
                            offset += 6;
                            break;
                        case Short:
                            unsafe.putShort(res + offset, data.value());
                            offset += 6;
                            break;
                        case Int:
                            offset += 2;
                            unsafe.putInt(res + offset, data.value());
                            offset += 4;
                            break;
                        case Long:
                            offset += 6;
                            unsafe.putLong(res + offset, data.value());
                            offset += 8;
                            break;
                        case Float:
                            offset += 2;
                            unsafe.putFloat(res + offset, data.value());
                            offset += 4;
                            break;
                        case Double:
                            offset += 6;
                            unsafe.putDouble(res + offset, data.value());
                            offset += 8;
                            break;
                        case String:
                            String str = data.value();
                            arr = str.getBytes(Charset.forName("UTF-8"));
                        case ByteArray:
                            offset += 2;
                            if (arr == null) arr = data.value();
                            unsafe.putInt(res + offset, arr.length);
                            offset += 4;
                            for (int i = 0; i < arr.length; ++i) {
                                unsafe.putByte(res + offset + i, arr[i]);
                            }
                            offset += arr.length;
                            if (arr.length % 8 != 0) {
                                // padding
                                offset += (8 - (arr.length % 8));
                            }
                            break;
                    }
                }
            }
            return new Pair<Long, Long>(size, res);
        } catch (Exception e) {
            unsafe.freeMemory(res);
            throw e;
        }
    }
}

