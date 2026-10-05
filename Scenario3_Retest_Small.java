public class Scenario3_Retest_Small {

    // TYPE 1 — Exact Clone
    public int multiply(int[] arr) {
        int p = 1;
        for (int v : arr) p *= v;
        return p;
    }

    public int product(int[] arr) {
        int p = 1;
        for (int v : arr) p *= v;
        return p;
    }

    // TYPE 2 — Renamed Clone
    public int countOdd(int[] arr) {
        int c = 0;
        for (int v : arr) if (v % 2 != 0) c++;
        return c;
    }

    public int tallyOdd(int[] nums) {
        int n = 0;
        for (int x : nums) if (x % 2 != 0) n++;
        return n;
    }

    // TYPE 3 — Near-Miss Clone
    public int last(int[] arr) {
        return arr[arr.length - 1];
    }

    public int lastSafe(int[] arr) {
        if (arr == null || arr.length == 0) return -1;
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
