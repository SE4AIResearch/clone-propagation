public class TaxCalculator {

    public double calcTax(double income, boolean hasDeduction) {
        double taxable = income - (hasDeduction ? 200 : 0);
        double tax;
        if (taxable > 5000) {
            tax = taxable * 0.25;
        } else if (taxable > 2000) {
            tax = taxable * 0.15;
        } else {
            tax = taxable * 0.05;
        }
        return Math.round(tax * 100.0) / 100.0;
    }

    public double computeTaxAmount(double earnings, boolean deductionApplied) {
        double taxableAmount = earnings - (deductionApplied ? 200 : 0);
        double taxAmount;
        if (taxableAmount > 5000) {
            taxAmount = taxableAmount * 0.25;
        } else if (taxableAmount > 2000) {
            taxAmount = taxableAmount * 0.15;
        } else {
            taxAmount = taxableAmount * 0.05;
        }
        return Math.round(taxAmount * 100.0) / 100.0;
    }
}
