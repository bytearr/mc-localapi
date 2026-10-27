package localapi;

/** Inclusive bounds, validated before iterating on the game thread. */
record BlockRegion(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
    long volume() {
        long x = (long) maxX - minX + 1;
        long y = (long) maxY - minY + 1;
        long z = (long) maxZ - minZ + 1;
        if (x <= 0 || y <= 0 || z <= 0) return 0;
        // Bound each factor first: even the product of three int spans can overflow a long.
        if (x > 32768 || y > 32768 || z > 32768 || x * y * z > 32768)
            throw new HttpError(400, "region too large (max 32768 blocks)");
        return x * y * z;
    }
}
