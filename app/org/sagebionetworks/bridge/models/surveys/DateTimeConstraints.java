package org.sagebionetworks.bridge.models.surveys;

public class DateTimeConstraints extends Constraints {

    protected boolean allowFuture = false;
    
    @Override
    public String getDataType() {
        return "datetime";
    }
    public boolean isAllowFuture() {
        return allowFuture;
    }
    public void setAllowFuture(boolean allowFuture) {
        this.allowFuture = allowFuture;
    }
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + (allowFuture ? 1231 : 1237);
        return result;
    }
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        DateTimeConstraints other = (DateTimeConstraints) obj;
        if (allowFuture != other.allowFuture)
            return false;
        return true;
    }
    @Override
    public String toString() {
        return "DateTimeConstraints [allowFuture=" + allowFuture + ", allowMultiple=" + allowMultiple + "]";
    }
}
