/**
 * The code in this file is from the Google Guava project.
 * A few minor modifications were made to inline the Ints.saturatedCast() function.
 * 
 * Original code:
 * https://github.com/google/guava/blob/master/guava/src/com/google/common/math/IntMath.java
 * https://github.com/google/guava/blob/master/guava/src/com/google/common/math/LongMath.java
 * https://github.com/google/guava/blob/master/guava/src/com/google/common/primitives/Ints.java
 * 
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

public class Guava {

	/**
	 * Returns the sum of {@code a} and {@code b} unless it would overflow or underflow in which case
	 * {@code Long.MAX_VALUE} or {@code Long.MIN_VALUE} is returned, respectively.
	 *
	 * @since 20.0
	 */
	public static long saturatedAdd(long a, long b) {
		long naiveSum = a + b;
		if ((a ^ b) < 0 | (a ^ naiveSum) >= 0) {
			// If a and b have different signs or a has the same sign as the result then there was no
			// overflow, return.
			return naiveSum;
		}
		// we did over/under flow, if the sign is negative we should return MAX otherwise MIN
		return Long.MAX_VALUE + ((naiveSum >>> (Long.SIZE - 1)) ^ 1);
	}

	/**
	 * Returns the difference of {@code a} and {@code b} unless it would overflow or underflow in
	 * which case {@code Long.MAX_VALUE} or {@code Long.MIN_VALUE} is returned, respectively.
	 *
	 * @since 20.0
	 */
	public static long saturatedSubtract(long a, long b) {
		long naiveDifference = a - b;
		if ((a ^ b) >= 0 | (a ^ naiveDifference) >= 0) {
			// If a and b have the same signs or a has the same sign as the result then there was no
			// overflow, return.
			return naiveDifference;
		}
		// we did over/under flow
		return Long.MAX_VALUE + ((naiveDifference >>> (Long.SIZE - 1)) ^ 1);
	}

	/**
	 * Returns the product of {@code a} and {@code b} unless it would overflow or underflow in which
	 * case {@code Long.MAX_VALUE} or {@code Long.MIN_VALUE} is returned, respectively.
	 *
	 * @since 20.0
	 */
	public static long saturatedMultiply(long a, long b) {
		// see checkedMultiply for explanation
		int leadingZeros =
				Long.numberOfLeadingZeros(a)
				+ Long.numberOfLeadingZeros(~a)
				+ Long.numberOfLeadingZeros(b)
				+ Long.numberOfLeadingZeros(~b);
		if (leadingZeros > Long.SIZE + 1) {
			return a * b;
		}
		// the return value if we will overflow (which we calculate by overflowing a long :) )
		long limit = Long.MAX_VALUE + ((a ^ b) >>> (Long.SIZE - 1));
		if (leadingZeros < Long.SIZE | (a < 0 & b == Long.MIN_VALUE)) {
			// overflow
			return limit;
		}
		long result = a * b;
		if (a == 0 || result / a == b) {
			return result;
		}
		return limit;
	}

	/**
	 * Returns the sum of {@code a} and {@code b} unless it would overflow or underflow in which case
	 * {@code Integer.MAX_VALUE} or {@code Integer.MIN_VALUE} is returned, respectively.
	 *
	 * @since 20.0
	 */
	public static int saturatedAdd(int a, int b) {
		long value = (long) a + b;
		if (value > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		if (value < Integer.MIN_VALUE) {
			return Integer.MIN_VALUE;
		}
		return (int) value;
	}

	/**
	 * Returns the difference of {@code a} and {@code b} unless it would overflow or underflow in
	 * which case {@code Integer.MAX_VALUE} or {@code Integer.MIN_VALUE} is returned, respectively.
	 *
	 * @since 20.0
	 */
	public static int saturatedSubtract(int a, int b) {
		long value = (long) a - b;
		if (value > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		if (value < Integer.MIN_VALUE) {
			return Integer.MIN_VALUE;
		}
		return (int) value;
	}

	/**
	 * Returns the product of {@code a} and {@code b} unless it would overflow or underflow in which
	 * case {@code Integer.MAX_VALUE} or {@code Integer.MIN_VALUE} is returned, respectively.
	 *
	 * @since 20.0
	 */
	public static int saturatedMultiply(int a, int b) {
		long value = (long) a * b;
		if (value > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		if (value < Integer.MIN_VALUE) {
			return Integer.MIN_VALUE;
		}
		return (int) value;
	}

}
