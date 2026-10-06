import java.util.*;

public class Scenario3_Large {

    public String formatCurrency(double amount) {
        return String.format("$%.2f", amount);
    }

    public boolean isValidEmail(String email) {
        return email != null && email.contains("@") && email.contains(".");
    }

    // TYPE 1 PAIR #1
    public int sumList(List<Integer> nums) {
        int total = 0;
        for (int n : nums) {
            total += n;
        }
        return total;
    }

    public void logMessage(String msg) {
        System.out.println("[LOG] " + msg);
    }

    public int addAllNumbers(List<Integer> nums) {
        int total = 0;
        for (int n : nums) {
            total += n;
        }
        return total;
    }

    // TYPE 1 PAIR #2
    public boolean isEmpty(List<?> list) {
        return list == null || list.size() == 0;
    }

    public boolean isListEmpty(List<?> list) {
        return list == null || list.size() == 0;
    }

    public String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // TYPE 2 PAIR #1
    public int countEven(int[] arr) {
        int count = 0;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] % 2 == 0) {
                count++;
            }
        }
        return count;
    }

    public int tallyEvens(int[] nums) {
        int tally = 0;
        for (int j = 0; j < nums.length; j++) {
            if (nums[j] % 2 == 0) {
                tally++;
            }
        }
        return tally;
    }

    public double average(int[] arr) {
        if (arr.length == 0) return 0.0;
        int sum = 0;
        for (int v : arr) sum += v;
        return (double) sum / arr.length;
    }

    // TYPE 2 PAIR #2
    public String joinWithComma(String[] words) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            result.append(words[i]);
            if (i < words.length - 1) result.append(", ");
        }
        return result.toString();
    }

    public String combineWithComma(String[] items) {
        StringBuilder output = new StringBuilder();
        for (int k = 0; k < items.length; k++) {
            output.append(items[k]);
            if (k < items.length - 1) output.append(", ");
        }
        return output.toString();
    }

    public int[] reverseArray(int[] arr) {
        int[] result = new int[arr.length];
        for (int i = 0; i < arr.length; i++) {
            result[i] = arr[arr.length - 1 - i];
        }
        return result;
    }

    // TYPE 3 PAIR #1
    public int maxOf(int[] arr) {
        int max = arr[0];
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > max) max = arr[i];
        }
        return max;
    }

    public int maxOfSafe(int[] arr) {
        if (arr == null || arr.length == 0) return Integer.MIN_VALUE;
        int max = arr[0];
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > max) max = arr[i];
        }
        return max;
    }

    public boolean containsValue(int[] arr, int target) {
        for (int v : arr) {
            if (v == target) return true;
        }
        return false;
    }

    // TYPE 3 PAIR #2
    public int minOf(int[] arr) {
        int min = arr[0];
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] < min) min = arr[i];
        }
        return min;
    }

    public int minOfSafe(int[] arr) {
        if (arr == null || arr.length == 0) return Integer.MAX_VALUE;
        int min = arr[0];
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] < min) min = arr[i];
        }
        return min;
    }

    public List<Integer> range(int start, int end) {
        List<Integer> result = new ArrayList<>();
        for (int i = start; i < end; i++) {
            result.add(i);
        }
        return result;
    }

    // TYPE 4 PAIR #1
    public int powerIterative(int base, int exp) {
        int result = 1;
        for (int i = 0; i < exp; i++) {
            result *= base;
        }
        return result;
    }

    public int powerRecursive(int base, int exp) {
        if (exp == 0) {
            return 1;
        }
        return base * powerRecursive(base, exp - 1);
    }

    public String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    // TYPE 4 PAIR #2
    public boolean isPalindromeLoop(String s) {
        int left = 0, right = s.length() - 1;
        while (left < right) {
            if (s.charAt(left) != s.charAt(right)) {
                return false;
            }
            left++;
            right--;
        }
        return true;
    }

    public boolean isPalindromeRecursive(String s) {
        if (s.length() <= 1) {
            return true;
        }
        if (s.charAt(0) != s.charAt(s.length() - 1)) {
            return false;
        }
        return isPalindromeRecursive(s.substring(1, s.length() - 1));
    }

    public int[] mergeArrays(int[] a, int[] b) {
        int[] result = new int[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
