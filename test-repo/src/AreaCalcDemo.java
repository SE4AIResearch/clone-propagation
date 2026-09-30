public class AreaCalcDemo {

    public int computeArea(int length, int width) {
        return length * width;
    }

    public int calculateArea(int l, int w) {
        return l * w;
    }

    public static void main(String[] args) {
        AreaCalcDemo demo = new AreaCalcDemo();
        System.out.println("computeArea(5, 4)   = " + demo.computeArea(5, 4));
        System.out.println("calculateArea(5, 4) = " + demo.calculateArea(5, 4));
    }
}
