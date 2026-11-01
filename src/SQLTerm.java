public class SQLTerm {
    public String _strTableName;
    public String _strColumnName;
    public String _strOperator;
    public Object _objValue;

    public SQLTerm() {
    }

    public SQLTerm(String strTableName, String strColumnName, String strOperator, Object objValue) {
        _strTableName = strTableName;
        _strColumnName = strColumnName;
        _strOperator = strOperator;
        _objValue = objValue;
    }
}
