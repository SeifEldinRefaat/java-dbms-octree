import java.io.Serializable;
import java.util.Hashtable;

public class Tuple implements Serializable {
    private static final long serialVersionUID = 1L;
    private final Hashtable<String, Object> values;

    public Tuple(Hashtable<String, Object> values) {
        this.values = new Hashtable<>(values);
    }

    public Object get(String column) {
        return values.get(column);
    }

    public Tuple with(Hashtable<String, Object> changes) {
        Tuple copy = new Tuple(values);
        copy.values.putAll(changes);
        return copy;
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
