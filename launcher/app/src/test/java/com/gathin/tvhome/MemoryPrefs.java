package com.gathin.tvhome;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SharedPreferences in memory, for JVM tests: strings and ints, listeners told of each key an edit changes. */
final class MemoryPrefs implements SharedPreferences {

    private final Map<String, Object> values = new HashMap<>();
    private final List<OnSharedPreferenceChangeListener> listeners = new ArrayList<>();

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(values);
    }

    @Override
    public String getString(String key, String defValue) {
        Object v = values.get(key);
        return v instanceof String ? (String) v : defValue;
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        return defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        Object v = values.get(key);
        return v instanceof Integer ? (Integer) v : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        return defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        return defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return defValue;
    }

    @Override
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new Editor() {
            private final Map<String, Object> puts = new HashMap<>();
            private final List<String> removes = new ArrayList<>();

            @Override
            public Editor putString(String key, String value) {
                puts.put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> v) {
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                puts.put(key, value);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                return this;
            }

            @Override
            public Editor remove(String key) {
                removes.add(key);
                return this;
            }

            @Override
            public Editor clear() {
                return this;
            }

            @Override
            public boolean commit() {
                apply();
                return true;
            }

            @Override
            public void apply() {
                List<String> changed = new ArrayList<>(removes);
                for (String k : removes) values.remove(k);
                values.putAll(puts);
                changed.addAll(puts.keySet());
                for (String k : changed) {
                    for (OnSharedPreferenceChangeListener l : new ArrayList<>(listeners)) {
                        l.onSharedPreferenceChanged(MemoryPrefs.this, k);
                    }
                }
            }
        };
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        listeners.add(l);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        listeners.remove(l);
    }
}
