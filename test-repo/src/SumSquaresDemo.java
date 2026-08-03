public class SumSquaresDemo {
    public int sumOfSquares(int n) {
        int total = 0;
        for (int i = 1; i <= n; i++) {
            total += i * i;
        }
        return total;
    }

    public int sumOfSquaresExact(int n) {
        int total = 0;
        for (int i = 1; i <= n; i++) {
            total += i * i;
        }
        return total;
    }
}
