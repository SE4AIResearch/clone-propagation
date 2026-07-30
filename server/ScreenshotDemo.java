public class ScreenshotDemo {
    public int sumOfSquaresLoop(int[] arr) {
        int total = 0;
        for (int i = 0; i < arr.length; i++) {
            total += arr[i] * arr[i];
        }
        return total;
    }
    public int totalSquared(int[] values) {
        int sum = 0;
        for (int j = 0; j < values.length; j++) {
            sum += values[j] * values[j];
        }
        return sum;
    }
}
