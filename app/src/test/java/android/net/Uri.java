package android.net;

/**
 * Unit tests run against Android's mockable jar, whose {@code Uri.parse} throws.
 * Readium locators need a real parse so the reopen-hold tests can use {@code Locator}.
 */
public final class Uri {
    private final String value;

    private Uri(String value) {
        this.value = value == null ? "" : value;
    }

    public static Uri parse(String value) {
        return new Uri(value);
    }

    public boolean isAbsolute() {
        return value.contains("://");
    }

    public boolean isRelative() {
        return !isAbsolute();
    }

    public String getFragment() {
        int mark = value.indexOf('#');
        if (mark < 0 || mark == value.length() - 1) return null;
        return value.substring(mark + 1);
    }

    public Builder buildUpon() {
        return new Builder(value);
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Uri && value.equals(((Uri) other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    public static final class Builder {
        private String value;

        private Builder(String value) {
            this.value = value;
        }

        public Builder fragment(String fragment) {
            int mark = value.indexOf('#');
            String base = mark < 0 ? value : value.substring(0, mark);
            value = fragment == null || fragment.isEmpty() ? base : base + "#" + fragment;
            return this;
        }

        public Uri build() {
            return new Uri(value);
        }
    }
}
