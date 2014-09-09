package nxt.http;

import nxt.Currency;
import nxt.util.Convert;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import javax.servlet.http.HttpServletRequest;

public final class GetCurrencyIds extends APIServlet.APIRequestHandler {

    static final GetCurrencyIds instance = new GetCurrencyIds();

    private GetCurrencyIds() {
        super(new APITag[] {APITag.MS}, "firstIndex", "lastIndex");
    }

    @Override
    JSONStreamAware processRequest(HttpServletRequest req) {

        int firstIndex = ParameterParser.getFirstIndex(req);
        int lastIndex = ParameterParser.getLastIndex(req);

        JSONArray currencyIds = new JSONArray();
        for (Currency currency : Currency.getAllCurrencies(firstIndex, lastIndex)) {
            currencyIds.add(Convert.toUnsignedLong(currency.getId()));
        }
        JSONObject response = new JSONObject();
        response.put("currencyIds", currencyIds);
        return response;
    }

}
