public class VowelCountDemo {

    public int countVowels(String text) {
    return coreCountVowelsExact(text);
}

    public int countVowelsExact(String text) {
    return coreCountVowelsExact(text);
}

private static int coreCountVowelsExact(String text) {
    int count = 0;
    String lower = text.toLowerCase();
    for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (ch == 'a' || ch == 'e' || ch == 'i' || ch == 'o' || ch == 'u') {
                count++;
            }
        }
    return count;
}
}
