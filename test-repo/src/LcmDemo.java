public class LcmDemo {

    public int lcmIterative(int a, int b) {
        int x = a, y = b;
        while (y != 0) {
            int t = y;
            y = x % y;
            x = t;
        }
        return (a * b) / x;
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
