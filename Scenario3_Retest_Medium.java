public class Scenario3_Retest_Medium {

    public String trim(String s) {
        return s == null ? null : s.trim();
    }

    // TYPE 1
    public int sumArray(int[] arr) {
    return coreAddAllElements(arr);
}

    public boolean isPositive(int n) {
        return n > 0;
    }

    public int addAllElements(int[] arr) {
    return coreAddAllElements(arr);
}

private static int coreAddAllElements(int[] arr) {
    int total = 0;
    for (int i = 0; i < arr.length; i++) {
            total += arr[i];
        }
    return total;
}

    // TYPE 2
    public int countPositive(int[] values) {
    return coreTallyPositiveNumbers(values);
}

    public String reverse(String s) {
        return new StringBuilder(s).reverse().toString();
    }

    public int tallyPositiveNumbers(int[] nums) {
    return coreTallyPositiveNumbers(nums);
}

private static int coreTallyPositiveNumbers(int[] values) {
    int count = 0;
    for (int i = 0; i < values.length; i++) {
            if (values[i] > 0) {
                count++;
            }
        }
    return count;
}

    // TYPE 3 — a single bare-return pair, same shape as last()/lastSafe()
    public int first(int[] arr) {
    return coreFirstSafe(arr);
}

    public double square(double x) {
        return x * x;
    }

    public int firstSafe(int[] arr) {
    if (arr == null || arr.length == 0) {
            return -1;
        }
    return coreFirstSafe(arr);
}

private static int coreFirstSafe(int[] arr) {
    return arr[0];
}

    public boolean isEven(int n) {
        return n % 2 == 0;
    }

    public int[] mergeArrays(int[] a, int[] b) {
        int[] result = new int[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    // TYPE 4
    public String joinLoop(String[] parts) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            result.append(parts[i]);
        }
        return result.toString();
    }

    public String joinRecursive(String[] parts, int index) {
        if (index >= parts.length) {
            return "";
        }
        return parts[index] + joinRecursive(parts, index + 1);
    }
}
