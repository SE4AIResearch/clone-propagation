public class Scenario3_Retest_Small {

    // TYPE 1 — Exact Clone
    public int multiply(int[] arr) {
    return coreProduct(v, arr);
}

    public int product(int[] arr) {
    return coreProduct(v, arr);
}

private static int coreProduct(Object v, int[] arr) {
    int p = 1;
    for (int v : arr) p *= v;
    return p;
}

    // TYPE 2 — Renamed Clone
    public int countOdd(int[] arr) {
    return coreTallyOdd(v, arr);
}

    public int tallyOdd(int[] nums) {
    return coreTallyOdd(x, nums);
}

private static int coreTallyOdd(Object v, int[] arr) {
    int c = 0;
    for (int v : arr) if (v % 2 != 0) c++;
    return c;
}

    // TYPE 3 — Near-Miss Clone
    public int last(int[] arr) {
    return coreLastSafe(arr);
}

    public int lastSafe(int[] arr) {
    if (arr == null || arr.length == 0) return -1;
    return coreLastSafe(arr);
}

private static int coreLastSafe(int[] arr) {
    return arr[arr.length - 1];
}

    // TYPE 4 — Semantic Clone
    // NOTE: hits the known, documented limitation — expected to NOT
    // be detected. Both sides only share common operators (*, <) with
    // no meaningful shared variable names.
    public int productLoop(int n) {
        int p = 1;
        for (int i = 1; i <= n; i++) p *= i;
        return p;
    }

    public int productRecursive(int n) {
        if (n <= 1) return 1;
        return n * productRecursive(n - 1);
    }
}
