public class PrDemo {

    public int countEvenNumbers(int[] arr) {
        int count = 0;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] % 2 == 0) {
                count++;
            }
        }
        return count;
    }

    public int evenCount(int[] values) {
        int total = 0;
        for (int j = 0; j < values.length; j++) {
            if (values[j] % 2 == 0) {
                total++;
            }
        }
        return total;
    }

    public static void main(String[] args) {
        PrDemo demo = new PrDemo();
        int[] arr = {1, 2, 3, 4, 5, 6};
        System.out.println("countEvenNumbers = " + demo.countEvenNumbers(arr));
        System.out.println("evenCount = " + demo.evenCount(arr));
    }
}
