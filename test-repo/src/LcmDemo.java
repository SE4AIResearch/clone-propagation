public class LcmDemo {

    public int lcmIterative(int a, int b) {
    return lcmRecursive(a, b);
}

    public int lcmRecursive(int a, int b) {
        return lcmRecursiveHelper(a, b, a, b);
    }

    private int lcmRecursiveHelper(int a, int b, int x, int y) {
        if (y == 0) {
            return (a * b) / x;
        }
        return lcmRecursiveHelper(a, b, y, x % y);
    }
}
