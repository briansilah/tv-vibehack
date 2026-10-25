package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NetworkIconTest {

    @Test
    public void activeEthernetUsesEthernetIconEvenWhenWifiIsAvailable() {
        assertEquals(R.drawable.ic_ethernet, MainActivity.networkIcon(true, true));
    }

    @Test
    public void wifiConnectionUsesWifiIcon() {
        assertEquals(R.drawable.ic_wifi, MainActivity.networkIcon(true, false));
    }

    @Test
    public void unavailableWifiUsesEthernetIconWhenOffline() {
        assertEquals(R.drawable.ic_ethernet, MainActivity.networkIcon(false, false));
    }
}
