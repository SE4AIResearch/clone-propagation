public class Main {

    // Base function 1
    public static int calculateSum(int[] numbers) {
        int totalSum = 0;
        for (int number : numbers) {
            totalSum += number;
        }
        return totalSum;
    }

    // Base function 2
    public static int findMax(int[] numbers) {
        int max = numbers[0];
        for (int n : numbers) {
            if (n > max) max = n;
        }
        return max;
    }

    // Base function 3
    public static boolean isPrime(int number) {
        if (number < 2) return false;
        for (int i = 2; i <= Math.sqrt(number); i++) {
            if (number % i == 0) return false;
        }
        return true;
    }

    // Type 1 — Exact clone of calculateSum
    public static int sumNumbers(int[] numbers) {
        int totalSum = 0;
        for (int number : numbers) {
            totalSum += number;
        }
        return totalSum;
    }

    // Type 2 — Renamed clone of findMax
    public static int getMaxValue(int[] data) {
        int maximum = data[0];
        for (int value : data) {
            if (value > maximum) maximum = value;
        }
        return maximum;
    }

    // Type 4 — Semantic clone (iterative approach)
    public static int iterativeSum(int[] numbers) {
        int total = 0;
        int index = 0;
        while (index < numbers.length) {
            total = total + numbers[index];
            index++;
        }
        return total;
    }

    // Type 4 — Semantic clone (recursive approach)
    public static int recursiveSum(int[] numbers, int index) {
        if (index >= numbers.length) return 0;
        return numbers[index] + recursiveSum(numbers, index + 1);
    }
}
