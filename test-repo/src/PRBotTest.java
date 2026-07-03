public class PRBotTest {
    public int sumValues(int[] arr) {
        int total = 0;
        for (int i = 0; i < arr.length; i++) {
            total += arr[i];
        }
        return total;
    }
    public int addAllValues(int[] nums) {
        int sum = 0;
        for (int j = 0; j < nums.length; j++) {
            sum += nums[j];
        }
        return sum;
    }
    public boolean isPositive(int n) {
        return n > 0;
    }
}
