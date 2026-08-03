public class TaxCalculator {

    public double calcTax(double income, boolean hasDeduction) {
    return coreComputeTaxAmount(income, hasDeduction, tax, Math);
}

    public double computeTaxAmount(double earnings, boolean deductionApplied) {
    return coreComputeTaxAmount(earnings, deductionApplied, taxAmount, Math);
}

private static double coreComputeTaxAmount(double income, boolean hasDeduction, Object tax, Object Math) {
    double taxable = income - (hasDeduction ? 200 : 0);
    double tax;
    if (taxable > 5000) {
            tax = taxable * 0.25;
        }
    else if (taxable > 2000) {
            tax = taxable * 0.15;
        }
    else {
            tax = taxable * 0.05;
        }
    return Math.round(tax * 100.0) / 100.0;
}
}
