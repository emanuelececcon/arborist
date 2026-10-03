package io.github.emanuelececcon.arborist;

/** Block coordinates packed into one long, laid out like vanilla's BlockPos: 26 bits x, 26 bits z, 12 bits y. */
final class Pos {

    private static final int XZ_BITS = 26;
    private static final int Y_BITS = 12;
    private static final long XZ_MASK = (1L << XZ_BITS) - 1;
    private static final long Y_MASK = (1L << Y_BITS) - 1;
    private static final int Z_SHIFT = Y_BITS;
    private static final int X_SHIFT = Y_BITS + XZ_BITS;

    /** The six face neighbours, the only ones vanilla leaf distance looks at. */
    static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private Pos() {
    }

    static long pack(int x, int y, int z) {
        return ((x & XZ_MASK) << X_SHIFT) | ((z & XZ_MASK) << Z_SHIFT) | (y & Y_MASK);
    }

    static int x(long pos) {
        return (int) (pos >> X_SHIFT);
    }

    static int y(long pos) {
        return (int) (pos << (64 - Y_BITS) >> (64 - Y_BITS));
    }

    static int z(long pos) {
        return (int) (pos << (64 - X_SHIFT) >> (64 - XZ_BITS));
    }

    static long offset(long pos, int dx, int dy, int dz) {
        return pack(x(pos) + dx, y(pos) + dy, z(pos) + dz);
    }

    static int distanceSquared(long a, long b) {
        int dx = x(a) - x(b);
        int dy = y(a) - y(b);
        int dz = z(a) - z(b);
        return dx * dx + dy * dy + dz * dz;
    }

    static String format(long pos) {
        return x(pos) + " " + y(pos) + " " + z(pos);
    }
}
