public class Scenario3_Small {

    // TYPE 1 — Exact Clone
    public int sum(int[] arr) {
    return coreTotal(v, arr);
}

    public int total(int[] arr) {
    return coreTotal(v, arr);
}

private static int coreTotal(Object v, int[] arr) {
    int t = 0;
    for (int v : arr) t += v;
    return t;
}

    // TYPE 2 — Renamed Clone
    public int countNeg(int[] arr) {
    return coreTallyNeg(v, arr);
}

    public int tallyNeg(int[] nums) {
    return coreTallyNeg(x, nums);
}

private static int coreTallyNeg(Object v, int[] arr) {
    int c = 0;
    for (int v : arr) if (v < 0) c++;
    return c;
}

    // TYPE 3 — Near-Miss Clone
    public int first(int[] arr) {
        return arr[0];
    }

    public int firstSafe(int[] arr) {
        if (arr == null || arr.length == 0) return -1;
        return arr[0];
    }

    // TYPE 4 — Semantic Clone
    // NOTE: this pair hits the known, documented limitation — the bot
    // will NOT flag it. Both sides only share common operators (+, <)
    // with no meaningful shared variable names. Expected, not a bug.
    public int sumLoop(int n) {
        int s = 0;
        for (int i = 1; i <= n; i++) s += i;
        return s;
    }

    public int sumRecursive(int n) {
        if (n <= 0) return 0;
        return n + sumRecursive(n - 1);
    }
}
