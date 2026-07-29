public class SumSquaresDemo {

    public int sumOfSquaresLoop(int[] arr) {
    return coreTotalSquared(arr);
}

    public int totalSquared(int[] values) {
    return coreTotalSquared(values);
}

private static int coreTotalSquared(int[] arr) {
    int total = 0;
    for (int i = 0; i < arr.length; i++) {
            total += arr[i] * arr[i];
        }
    return total;
}

    public static void main(String[] args) {
        SumSquaresDemo demo = new SumSquaresDemo();
        int[] arr = {1, 2, 3, 4, 5};
        System.out.println("sumOfSquaresLoop = " + demo.sumOfSquaresLoop(arr));
        System.out.println("totalSquared = " + demo.totalSquared(arr));
    }
}
