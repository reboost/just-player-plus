package android.net;

import java.util.Collections;
import java.util.List;

public class FakeUri extends Uri {

    private final String uriString;

    public FakeUri(String uriString) {
        this.uriString = uriString;
    }

    @Override
    public boolean isHierarchical() {
        return true;
    }

    @Override
    public boolean isRelative() {
        return false;
    }

    @Override
    public String toString() {
        return uriString;
    }

    @Override
    public String getScheme() {
        return "file";
    }

    @Override
    public String getSchemeSpecificPart() {
        return uriString;
    }

    @Override
    public String getEncodedSchemeSpecificPart() {
        return uriString;
    }

    @Override
    public String getAuthority() {
        return "";
    }

    @Override
    public String getEncodedAuthority() {
        return "";
    }

    @Override
    public String getUserInfo() {
        return null;
    }

    @Override
    public String getEncodedUserInfo() {
        return null;
    }

    @Override
    public String getHost() {
        return "";
    }

    @Override
    public int getPort() {
        return -1;
    }

    @Override
    public String getPath() {
        return uriString;
    }

    @Override
    public String getEncodedPath() {
        return uriString;
    }

    @Override
    public String getQuery() {
        return null;
    }

    @Override
    public String getEncodedQuery() {
        return null;
    }

    @Override
    public String getFragment() {
        return null;
    }

    @Override
    public String getEncodedFragment() {
        return null;
    }

    @Override
    public List<String> getPathSegments() {
        return Collections.emptyList();
    }

    @Override
    public String getLastPathSegment() {
        return uriString;
    }

    @Override
    public Builder buildUpon() {
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof FakeUri) {
            return uriString.equals(((FakeUri) o).uriString);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return uriString.hashCode();
    }

    @Override
    public int compareTo(Uri o) {
        return toString().compareTo(o.toString());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(android.os.Parcel dest, int flags) {
    }
}
