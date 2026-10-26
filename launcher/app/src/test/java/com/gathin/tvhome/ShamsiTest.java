package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class ShamsiTest {
    @Test
    public void convertsNowruzAndThePreviousLeapDay() {
        assertArrayEquals(new int[]{1404, 1, 1}, Shamsi.toJalali(2025, 3, 21));
        assertArrayEquals(new int[]{1403, 12, 30}, Shamsi.toJalali(2025, 3, 20));
        assertArrayEquals(new int[]{1405, 1, 1}, Shamsi.toJalali(2026, 3, 21));
        assertArrayEquals(new int[]{1404, 12, 29}, Shamsi.toJalali(2026, 3, 20));
    }

    @Test
    public void convertsMonthAndYearBoundaries() {
        assertArrayEquals(new int[]{1405, 7, 27}, Shamsi.toJalali(2026, 10, 19));
        assertArrayEquals(new int[]{1405, 9, 30}, Shamsi.toJalali(2026, 12, 21));
        assertArrayEquals(new int[]{1405, 10, 1}, Shamsi.toJalali(2026, 12, 22));
    }
}
