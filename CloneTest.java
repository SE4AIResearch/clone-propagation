public class CloneTest {

    public int findMax(int[] arr) {
    return coreFindMaxSafe(arr);
}

    public int findMaxSafe(int[] arr) {
    if (arr == null || arr.length == 0) {
            return -1;
        }
    return coreFindMaxSafe(arr);
}

private static int coreFindMaxSafe(int[] arr) {
    int max = arr[0];
    for (int i = 1; i < arr.length; i++) {
            if (arr[i] > max) {
                max = arr[i];
            }
        }
    return max;
}
}
