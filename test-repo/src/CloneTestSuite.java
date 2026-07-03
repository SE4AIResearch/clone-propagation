public class RefactorDemo2 {

    // Clone Pair 1: Type 1
    public String repeatString(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    public String duplicateString(String s, int n) {
        return repeatString(s, n);
    }

    // Clone Pair 2: Type 2
    public boolean isPalindrome(String s) {
        int left = 0, right = s.length() - 1;
        while (left < right) {
            if (s.charAt(left) != s.charAt(right)) return false;
            left++;
            right--;
        }
        return true;
    }

    public boolean checkPalindrome(String word) {
        return isPalindrome(word);
    }

    // Should NOT match anything
    public boolean isEven(int n) {
        return n % 2 == 0;
    }

    public void printMessage(String msg) {
        System.out.println(msg);
    }
}

