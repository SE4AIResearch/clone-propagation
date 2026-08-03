public class SumSquaresDemo {
    public int sumOfSquares(int n) {
    return coreSumOfSquaresExact(n);
}

    public int sumOfSquaresExact(int n) {
    return coreSumOfSquaresExact(n);
}

private static int coreSumOfSquaresExact(int n) {
    int total = 0;
    for (int i = 1; i <= n; i++) {
            total += i * i;
        }
    return total;
}
}
