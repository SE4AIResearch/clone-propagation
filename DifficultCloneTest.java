public class DifficultCloneTest {

    // TYPE 1 — Exact clone
    public int totalA(int[] data) {
    return coreTotalB(data);
}

    public int totalB(int[] data) {
    return coreTotalB(data);
}

private static int coreTotalB(int[] data) {
    int result = 0;
    for (int i = 0; i < data.length; i++) {
            result += data[i];
        }
    return result;
}

    // TYPE 2 — Renamed variables + different parameter names
    public int positiveA(int[] values) {
    return corePositiveB(value, values);
}

    public int positiveB(int[] numbers) {
    return corePositiveB(number, numbers);
}

private static int corePositiveB(Object value, int[] values) {
    int count = 0;
    for (int value : values) {
            if (value > 0)
                count++;
        }
    return count;
}

    // TYPE 3 — Similar structure, but statements modified
    public int findMinA(int[] data) {
        int min = data[0];
        for (int i = 1; i < data.length; i++) {
            if (data[i] < min)
                min = data[i];
        }
        return min;
    }

    public int findMinB(int[] values) {
        int smallest = Integer.MAX_VALUE;
        for (int value : values) {
            if (value < smallest) {
                smallest = value;
            }
        }
        return smallest;
    }

    // TYPE 3 — Reordered logic + extra operation
    public boolean containsA(int[] data, int target) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == target)
                return true;
        }
        return false;
    }

    public boolean containsB(int[] numbers, int key) {
        boolean found = false;
        int i = 0;
        while (i < numbers.length && !found) {
            if (key == numbers[i])
                found = true;
            i++;
        }
        return found;
    }

    // TYPE 4 — Same behavior, completely different structure
    public int countA(int[] values) {
    return countB(values);
}

    public int countB(int[] values) {
        if (values.length == 0)
            return 0;
        return countRecursive(values, values.length - 1);
    }

    private int countRecursive(int[] values, int index) {
        if (index < 0)
            return 0;

        int current = values[index] % 2 == 0 ? 1 : 0;
        return current + countRecursive(values, index - 1);
    }

    // TYPE 4 — Same computation, different algorithm
    public int productA(int[] values) {
        int result = 1;
        for (int value : values) {
            result *= value;
        }
        return result;
    }

    public int productB(int[] values) {
    return productA(values);
}
}
