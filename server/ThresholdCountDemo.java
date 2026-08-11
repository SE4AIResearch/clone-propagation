public class ThresholdCountDemo {
    public int countAboveThreshold(int[] arr, int threshold) {
    return coreTallyAboveLimit(arr, threshold);
}
    public int tallyAboveLimit(int[] values, int limit) {
    return coreTallyAboveLimit(values, limit);
}

private static int coreTallyAboveLimit(int[] arr, int threshold) {
    int count = 0;
    for (int i = 0; i < arr.length; i++) {
            if (arr[i] > threshold) {
                count++;
            }
        }
    return count;
}
    public static void main(String[] args) {
        ThresholdCountDemo demo = new ThresholdCountDemo();
        int[] arr = {5, 3, 8, 1, 9, 2};
        System.out.println("countAboveThreshold = " + demo.countAboveThreshold(arr, 4));
        System.out.println("tallyAboveLimit = " + demo.tallyAboveLimit(arr, 4));
    }
}
