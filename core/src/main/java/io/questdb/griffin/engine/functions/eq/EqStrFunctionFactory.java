/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin.engine.functions.eq;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.sql.Function;
import io.questdb.cairo.sql.Record;
import io.questdb.griffin.AbstractBooleanFunctionFactory;
import io.questdb.griffin.FunctionFactory;
import io.questdb.griffin.engine.functions.BinaryFunction;
import io.questdb.griffin.engine.functions.BooleanFunction;
import io.questdb.griffin.engine.functions.UnaryFunction;
import io.questdb.std.Chars;
import io.questdb.std.ObjList;

public class EqStrFunctionFactory extends AbstractBooleanFunctionFactory implements FunctionFactory {
    @Override
    public String getSignature() {
        return "=(SS)";
    }

    @Override
    public Function newInstance(ObjList<Function> args, int position, CairoConfiguration configuration) {
        // there are optimisation opportunities
        // 1. when one of args is constant null comparison can boil down to checking
        //    length of non-constant (must be -1)
        // 2. when one of arguments is constant, save method call and use a field

        Function a = args.getQuick(0);
        Function b = args.getQuick(1);

        if (a.isConstant() && !b.isConstant()) {
            return createHalfConstantFunc(position, a, b, isNegated);
        }

        if (!a.isConstant() && b.isConstant()) {
            return createHalfConstantFunc(position, b, a, isNegated);
        }

        return new Func(position, a, b, isNegated);
    }

    private Function createHalfConstantFunc(int position, Function constFunc, Function varFunc, boolean isNegated) {
        CharSequence constValue = constFunc.getStr(null);

        if (constValue == null) {
            return new NullCheckFunc(position, varFunc, isNegated);
        }

        return new ConstCheckFunc(position, varFunc, constValue, isNegated);
    }

    private static class NullCheckFunc extends BooleanFunction implements UnaryFunction {
        private final boolean isNegated;
        private final Function arg;

        public NullCheckFunc(int position, Function arg, boolean isNegated) {
            super(position);
            this.arg = arg;
            this.isNegated = isNegated;
        }

        @Override
        public Function getArg() {
            return arg;
        }

        @Override
        public boolean getBool(Record rec) {
            return isNegated != (arg.getStrLen(rec) == -1L);
        }
    }

    private static class ConstCheckFunc extends BooleanFunction implements UnaryFunction {
        private final boolean isNegated;
        private final Function arg;
        private final CharSequence constant;

        public ConstCheckFunc(int position, Function arg, CharSequence constant, boolean isNegated) {
            super(position);
            this.arg = arg;
            this.constant = constant;
            this.isNegated = isNegated;
        }

        @Override
        public Function getArg() {
            return arg;
        }

        @Override
        public boolean getBool(Record rec) {
            return isNegated != Chars.equalsNc(constant, arg.getStr(rec));
        }
    }

    private static class Func extends BooleanFunction implements BinaryFunction {
        private final boolean isNegated;
        private final Function left;
        private final Function right;

        public Func(int position, Function left, Function right, boolean isNegated) {
            super(position);
            this.left = left;
            this.right = right;
            this.isNegated = isNegated;
        }

        @Override
        public Function getLeft() {
            return left;
        }

        @Override
        public Function getRight() {
            return right;
        }

        @Override
        public boolean getBool(Record rec) {
            // important to compare A and B strings in case
            // these are columns of the same record
            // records have re-usable character sequences
            final CharSequence a = left.getStr(rec);
            final CharSequence b = right.getStrB(rec);

            if (a == null) {
                return isNegated != (b == null);
            }

            return isNegated != (b != null && Chars.equals(a, b));
        }
    }
}
