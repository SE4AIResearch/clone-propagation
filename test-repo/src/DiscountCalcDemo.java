public class DiscountCalcDemo {

    public double computeDiscountPrice(double price, double discountPct, boolean isMember) {
        double discount = price * (discountPct / 100);
        double finalPrice = price - discount;
        if (isMember) {
            finalPrice *= 0.95;
        }
        return Math.round(finalPrice * 100.0) / 100.0;
    }

    public double calculateDiscountedPrice(double price, double discountPct, boolean isMember) {
        double discount = price * (discountPct / 100);
        double finalPrice = price - discount;
        if (isMember) {
            finalPrice *= 0.95;
        }
        return Math.round(finalPrice * 100.0) / 100.0;
    }

    public static void main(String[] args) {
        DiscountCalcDemo demo = new DiscountCalcDemo();
        System.out.println("computeDiscountPrice(100,10,true)      = " + demo.computeDiscountPrice(100, 10, true));
        System.out.println("calculateDiscountedPrice(100,10,true)  = " + demo.calculateDiscountedPrice(100, 10, true));
    }
}
