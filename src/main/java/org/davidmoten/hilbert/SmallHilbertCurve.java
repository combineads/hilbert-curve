package org.davidmoten.hilbert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.github.davidmoten.guavamini.Lists;
import com.github.davidmoten.guavamini.Preconditions;
import com.github.davidmoten.guavamini.annotations.VisibleForTesting;

/**
 * Converts between Hilbert index ({@code BigInteger}) and N-dimensional points.
 * 
 * <p>
 * Note: This algorithm is derived from work done by John Skilling and published
 * in "Programming the Hilbert curve". (c) 2004 American Institute of Physics.
 * With thanks also to Paul Chernoch who published a C# algorithm for Skilling's
 * work on StackOverflow and
 * <a href="https://github.com/paulchernoch/HilbertTransformation">GitHub</a>).
 */
public final class SmallHilbertCurve {

    private final int bits;
    private final int dimensions;
    private final int length;

    private SmallHilbertCurve(int bits, int dimensions) {
        this.bits = bits;
        this.dimensions = dimensions;
        this.length = bits * dimensions;
    }

    /**
     * Converts a point to its Hilbert curve index.
     * 
     * @param point
     *            an array of {@code long}. Each coordinate can be between 0 and
     *            2<sup>bits</sup>-1.
     * @return index {@code long} in the range 0 to 2<sup>bits * dimensions</sup> -
     *         1
     * @throws IllegalArgumentException
     *             if length of point array is not equal to the number of
     *             dimensions.
     */
    public long index(long... point) {
        Preconditions.checkArgument(point.length == dimensions);
        return toIndex(HilbertCurve.transposedIndex(bits, point));
    }

    /**
     * Converts a {@code long} index (distance along the Hilbert Curve from 0) to a
     * point of dimensions defined in the constructor of {@code this}.
     * 
     * @param index
     *            index along the Hilbert Curve from 0. Maximum value 2 <sup>bits *
     *            dimensions</sup>-1.
     * @return array of longs being the point
     * @throws IllegalArgumentException
     *             if index is negative
     */
    public long[] point(long index) {
        return HilbertCurve.transposedIndexToPoint(bits, transposeLong(index));
    }

    // untranspose
    private long toIndex(long... transposedIndex) {
        long b = 0;
        int bIndex = length - 1;
        long mask = 1L << (bits - 1);
        for (int i = 0; i < bits; i++) {
            for (int j = 0; j < transposedIndex.length; j++) {
                if ((transposedIndex[j] & mask) != 0) {
                    b |= 1L << bIndex;
                }
                bIndex--;
            }
            mask >>= 1;
        }
        // b is expected to be BigEndian
        return b;
    }

    private long[] transposeLong(long index) {
        long[] x = new long[dimensions];
        for (int idx = 0; idx < 64; idx++) {
            if ((index & (1L << idx)) != 0) {
                int dim = (length - idx - 1) % dimensions;
                int shift = (idx / dimensions) % bits;
                x[dim] |= 1L << shift;
            }
        }
        return x;
    }

    // Brute force is to travel the bounding surface of the bounding
    // hyperrectangle looking for max and min index values and return a single
    // range. This method is potentially very wasteful because the Hilbert curve
    // has locality discontinuities recursively at divisors of 2 in the domain
    // and it is unnecessary to travel the whole bounding surface as it can be
    // solved more efficiently using an analytical recursive technique.
    //
    // Minimal force is to recursively break the bounding box up into
    // smaller boxes so that the discontinuities have progressively less
    // effect. We stop the recursive process when the probability of missing an
    // index is appropriately low (for further consideration).
    public List<Range> query(long[] a, long[] b, int splitDepth) {
        Preconditions.checkArgument(a.length == dimensions);
        Preconditions.checkArgument(b.length == dimensions);
        List<List<Range>> rangesByDimension = Lists.newArrayList();
        for (int i = 0; i < dimensions; i++) {
            rangesByDimension.add( //
                    new Range(Math.min(a[i], b[i]), //
                            Math.max(a[i], b[i])) //
                                    .split(splitDepth));
        }
        // combine coordinate ranges from each dimension and from boxes
        // determine the indexes of the corners of the boxes. The min max of the
        // box corner indexes are the ranges returned by this method.
        return Range.simplify(hilbertIndexRanges(rangesByDimension, dimensions));
    }

    private List<Range> hilbertIndexRanges(List<List<Range>> rangesByDimension, int n) {
        Function<Integer, Integer> indexMax = i -> rangesByDimension.get(i).size();
        List<Range> ranges = new ArrayList<Range>();
        int[] indexes = new int[dimensions];
        // for every combination of ranges
        do {
            // do something with indexes
            List<Range> rangesToCombine = new ArrayList<>();
            for (int i = 0; i < dimensions; i++) {
                rangesToCombine.add(rangesByDimension.get(i).get(indexes[i]));
            }

            // cross-product the point coordinates and calculate the min and max
            // hilbert indexes to get the range for this hyper-rectangle
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            for (int i = 0; i < Math.pow(2, dimensions); i++) {
                long point[] = new long[dimensions];
                for (int j = 0; j < dimensions; j++) {
                    Range r = rangesToCombine.get(j);
                    long x;
                    if ((i & (1 << j)) == 0) {
                        if (r.low() == rangesByDimension.get(j).get(0).low()) {
                            x = r.low();
                        } else {
                            x = Math.min(r.high(), r.low() + 1);
                        }
                    } else {
                        x = r.high();
                    }
                    point[j] = x;
                }
                long h = index(point);
                if (h < min) {
                    min = h;
                } else if (h > max) {
                    max = h;
                }
            }
            ranges.add(new Range(min, max));

            // add one
            for (int i = 0; i < dimensions; i++) {
                indexes[i] = (indexes[i] + 1) % indexMax.apply(i);
                if (indexes[i] != 0) {
                    break;
                }
            }
        } while (!Util.allZero(indexes));

        return ranges;
    }

    public List<Range> query2(long[] a, long[] b, int splitDepth) {
        // we split into 2^splitDepth parts (boxes)
        // map each box to a Range based on the hilbert index of the corners of the box
        // sort the ranges by lower()
        // combine overlapping or contiguous ranges
        Preconditions.checkArgument(a.length == dimensions);
        Preconditions.checkArgument(b.length == dimensions);
        Preconditions.checkArgument(splitDepth >= 0);
        List<Box> boxes = split(a, b, splitDepth);
        List<Range> ranges = boxes.stream().map(x -> toRange(x)).collect(Collectors.toList());
        boxes.size();
        // combine coordinate ranges from each dimension and from boxes
        // determine the indexes of the corners of the boxes. The min max of the
        // box corner indexes are the ranges returned by this method.

        // TODO
        return ranges;
    }

    @VisibleForTesting
    Range toRange(Box box) {
        int dimensions = box.a.length;
        // get min and max index value using all the corners of the box
        Long min = null;
        Long max = null;
        for (long i = 0; i < 1 << dimensions; i++) {
            long[] x = new long[box.a.length];
            for (int j = 0; j < dimensions; j++) {
                if ((i & (1L << j)) != 0) {
                    // if jth bit set
                    x[j] = box.a[j];
                } else {
                    x[j] = box.b[j];
                }
            }
            long index = index(x);
            if (min == null || index < min) {
                min = index;
            }
            if (max == null || index > max) {
                max = index;
            }
        }
        return new Range(min, max);
    }

    static List<Box> split(long[] a, long[] b, int splitDepth) {
        int dimensions = a.length;
        List<Box> boxes = Lists.newArrayList();
        boxes.add(new Box(a, b));
        for (int depth = 1; depth <= splitDepth; depth++) {
            for (int i = 0; i < dimensions; i++) {
                List<Box> boxes2 = Lists.newArrayList();
                for (Box box : boxes) {
                    long low = Math.min(box.a[i], box.b[i]);
                    long high = Math.max(box.a[i], box.b[i]);
                    long sigBetween = Util.mostSignificantBetween(low + 1, high + 1) - 1;
                    {
                        long[] a2 = copy(box.a);
                        long[] b2 = copy(box.b);
                        a2[i] = low;
                        b2[i] = sigBetween;

                        long[] a3 = copy(box.a);
                        long[] b3 = copy(box.b);
                        a3[i] = Math.min(sigBetween + 1, high);
                        b3[i] = high;
                        boxes2.add(new Box(a2, b2));
                        boxes2.add(new Box(a3, b3));
                    }
                }
                boxes = boxes2;
            }
        }
        return boxes;
    }

    private static long[] copy(long[] a) {
        return Arrays.copyOf(a, a.length);
    }

    public static final class Builder {
        private int bits;

        Builder() {
            // private instantiation
        }

        public Builder bits(int bits) {
            this.bits = bits;
            return this;
        }

        public SmallHilbertCurve dimensions(int dimensions) {
            Preconditions.checkArgument(bits * dimensions <= 63, "bits * dimensions must be less than or equal to 63");
            return new SmallHilbertCurve(bits, dimensions);
        }

    }
}