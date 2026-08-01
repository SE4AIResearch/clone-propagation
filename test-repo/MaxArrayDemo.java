public class MaxArrayDemo {

    public int maxOfArrayLoop(int[] arr) {
        int m = arr[0];
        for (int x : arr) {
            if (x > m) {
                m = x;
            }
        }
        return m;
    }

    public int maxOfArrayRecursive(int[] arr, int idx, int best) {
        if (idx == arr.length) {
            return best;
        }
        if (arr[idx] > best) {
            best = arr[idx];
        }
        return maxOfArrayRecursive(arr, idx + 1, best);
    }
}

