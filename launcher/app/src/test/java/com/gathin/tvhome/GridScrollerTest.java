package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GridScrollerTest {

    private static final int NONE = Integer.MIN_VALUE;
    private static final int VIEW = 800;

    private static int target(int top, int bottom, int dockAtTop, int minDy, int maxDy) {
        return GridScroller.rowTarget(top, bottom, 0, VIEW, dockAtTop, minDy, maxDy);
    }

    @Test
    public void immediateScrollIncludesTheUnfinishedSpring() {
        int pending = GridScroller.scrollDelta(630f, 200);
        assertEquals(430, pending);
        assertEquals(630, 200 + pending);
        assertEquals(0, 200 + pending - 630);
    }

    @Test
    public void immediateScrollFinishesAnUpwardSpring() {
        assertEquals(-430, GridScroller.scrollDelta(-630f, -200));
        assertEquals(0, GridScroller.scrollDelta(-630f, -200) + 430);
    }

    @Test
    public void pendingAndAppliedScrollUseTheSamePixelRounding() {
        assertEquals(1, GridScroller.scrollDelta(20.6f, 20));
        assertEquals(0, GridScroller.scrollDelta(20.6f, 21));
        assertEquals(-1, GridScroller.scrollDelta(-20.6f, -20));
        assertEquals(0, GridScroller.scrollDelta(-20.6f, -21));
    }

    @Test
    public void downFromTheDockPutsTheDockRowAtTheTop() {
        // First grid row just below the parked dock; the dock row reaches the top after scrolling 500.
        assertEquals(500, target(740, 1050, 500, -10, Integer.MAX_VALUE));
    }

    @Test
    public void aShortGridScrollsOnlyAsFarAsItCan() {
        assertEquals(320, target(740, 1050, 500, -10, 320));
    }

    @Test
    public void deeperRowsMoveJustEnoughToShowTheirBottom() {
        // The dock row is already at the top (0) but the focused row ends 120 below the view.
        assertEquals(120, target(600, 920, 0, -300, Integer.MAX_VALUE));
    }

    @Test
    public void aVisibleRowDoesNotMoveThePageWithoutTheDock() {
        assertEquals(0, target(300, 600, NONE, NONE, Integer.MAX_VALUE));
    }

    @Test
    public void goingUpBringsTheDockBackWhenTheRowStillShows() {
        // The dock row is 150 above where it sits at the top; the row stays visible if the page moves back 150.
        assertEquals(-150, target(300, 600, -150, -400, Integer.MAX_VALUE));
    }

    @Test
    public void goingUpStopsWhereTheRowWouldLeaveTheBottom() {
        // Bringing the dock back all the way (-400) would push the row's bottom (700) off the view.
        assertEquals(-100, target(300, 700, -400, -600, Integer.MAX_VALUE));
    }

    @Test
    public void aRowAboveTheViewScrollsBackToShowItsTop() {
        assertEquals(-80, target(-80, 230, NONE, NONE, Integer.MAX_VALUE));
    }

    @Test
    public void aRowTallerThanTheViewShowsItsTop() {
        assertEquals(40, target(40, 1000, NONE, NONE, Integer.MAX_VALUE));
    }

    @Test
    public void neverScrollsPastTheTop() {
        assertEquals(-30, target(-80, 230, NONE, -30, Integer.MAX_VALUE));
    }

    @Test
    public void homeLandsOnTvOsCurve() {
        // Measured on an Apple TV: half-way after about 200 ms, 90 % after about 470 ms.
        assertEquals(0.0, HomeMotion.settle(0), 1e-9);
        assertEquals(0.5, HomeMotion.settle(0.2), 0.03);
        assertEquals(0.9, HomeMotion.settle(0.47), 0.01);
        assertEquals(1.0, HomeMotion.settle(HomeMotion.LAND_MS / 1000.0), 0.006);
    }

    @Test
    public void scaleUpRectsAreCentred() {
        assertArrayEquals(new int[]{134, 75, 1651, 929}, Transitions.centred(1920, 1080, 0.86f));
        assertArrayEquals(new int[]{-144, -81, 2208, 1242}, Transitions.centred(1920, 1080, 1.15f));
        assertArrayEquals(new int[]{0, 0, 1920, 1080}, Transitions.centred(1920, 1080, 1f));
    }
}
