package nxt;

import nxt.db.Db;
import nxt.db.DbIterator;
import nxt.db.DbKey;
import nxt.db.VersionedEntityDbTable;
import nxt.util.Convert;
import nxt.util.Listener;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@SuppressWarnings("UnusedDeclaration")
public final class Currency {

    private static final DbKey.LongKeyFactory<Currency> currencyDbKeyFactory = new DbKey.LongKeyFactory<Currency>("id") {

        @Override
        public DbKey newKey(Currency currency) {
            return currency.dbKey;
        }

    };

    private static final VersionedEntityDbTable<Currency> currencyTable = new VersionedEntityDbTable<Currency>(currencyDbKeyFactory) {

        @Override
        protected String table() {
            return "currency";
        }

        @Override
        protected Currency load(Connection con, ResultSet rs) throws SQLException {
            return new Currency(rs);
        }

        @Override
        protected void save(Connection con, Currency currency) throws SQLException {
            currency.save(con);
        }
    };

    public static DbIterator<Currency> getAllCurrencies(int from, int to) {
        return currencyTable.getAll(from, to);
    }

    public static int getCount() {
        return currencyTable.getCount();
    }

    public static Currency getCurrency(Long id) {
        return currencyTable.get(currencyDbKeyFactory.newKey(id));
    }

    public static Currency getCurrencyByName(String name) {
        return currencyTable.getBy("name", name);
    }

    public static Currency getCurrencyByCode(String code) {
        return currencyTable.getBy("code", code);
    }

    public static DbIterator<Currency> getCurrencyIssuedBy(Long accountId, int from, int to) {
        return currencyTable.getManyBy("account_id", accountId, from, to);
    }

    static void addCurrency(Transaction transaction, Attachment.MonetarySystemCurrencyIssuance attachment) {
        currencyTable.insert(new Currency(transaction, attachment));
    }

    static {
        addNXTCurrency();

        Nxt.getBlockchainProcessor().addListener(new Listener<Block>() {
            @Override
            public void notify(Block block) {
                try (DbIterator<Currency> currencies = currencyTable.getAll(0, getCount() - 1)) {
                    while (currencies.hasNext()) {
                        Currency currency = currencies.next();
                        if (currency.getIssuanceHeight() > 0 && currency.getIssuanceHeight() <= block.getHeight()) {
                            if (currency.getCurrentReservePerUnitNQT() < currency.getMinReservePerUnitNQT()) {
                                try (DbIterator<CurrencyFounder> founders = CurrencyFounder.getCurrencyFounders(currency.getId())) {
                                    while (founders.hasNext()) {
                                        CurrencyFounder founder = founders.next();
                                        Account.getAccount(founder.getAccountId()).addToBalanceAndUnconfirmedBalanceNQT(founder.getValue());
                                    }
                                }
                                currencyTable.delete(currency);
                            }
                            CurrencyFounder.remove(currency.getId());
                        }
                    }
                }
            }
        }, BlockchainProcessor.Event.AFTER_BLOCK_APPLY);

    }

    static void addNXTCurrency() {
        if (isCodeSquatted("NXT")) {
            return;
        }
        try {
            Db.beginTransaction();
            addCurrency(0L, Genesis.GENESIS_BLOCK_ID, "Nxt", "NXT", "", (byte) 0, Constants.MAX_BALANCE_NQT, 0, 1, (byte) 0, (byte) 0, (byte) 0, Constants.MAX_BALANCE_NQT, 0);
            Db.commitTransaction();
        } catch (Exception e) {
            Db.rollbackTransaction();
            throw e;
        } finally {
            Db.endTransaction();
        }
    }

    static void addCurrency(Long currencyId, Long accountId, String name, String code, String description, byte type, long totalSupply, int issuanceHeight, long minReservePerUnitNQT, byte minDifficulty, byte maxDifficulty, byte ruleset, long currentSupply, long currentReservePerUnitNQT) {
        Currency currency = getCurrency(currencyId);
        if (currency != null) {
            throw new IllegalStateException("Currency with id " + Convert.toUnsignedLong(currencyId) + " already exists");
        }
        currency = getCurrencyByName(name);
        if (currency != null) {
            throw new IllegalStateException("Currency with name " + name + " already exists");
        }
        currency = getCurrencyByCode(code);
        if (currency != null) {
            throw new IllegalStateException("Currency with code " + code + " already exists");
        }
        currency = new Currency(currencyId, accountId, name, code, description, type, totalSupply, issuanceHeight, minReservePerUnitNQT, minDifficulty, maxDifficulty, ruleset, currentSupply, currentReservePerUnitNQT);
        currencyTable.insert(currency);
    }

    static boolean isNameSquatted(String name) {
        return getCurrencyByName(name) != null;
    }

    static boolean isCodeSquatted(String code) {
        return getCurrencyByCode(code) != null;
    }

    private final Long currencyId;

    private final DbKey dbKey;
    private final Long accountId;
    private final String name;
    private final String code;
    private final String description;
    private final byte type;
    private long totalSupply;
    private final int issuanceHeight;
    private final long minReservePerUnitNQT;
    private final byte minDifficulty;
    private final byte maxDifficulty;
    private final byte ruleset;
    private long currentSupply;

    private long currentReservePerUnitNQT;

    public Currency(Transaction transaction, Attachment.MonetarySystemCurrencyIssuance attachment) {
        this(transaction.getId(), transaction.getSenderId(), attachment.getName(), attachment.getCode(), attachment.getDescription(), attachment.getType(),
                attachment.getTotalSupply(), attachment.getIssuanceHeight(), attachment.getMinReservePerUnitNQT(),
                attachment.getMinDifficulty(), attachment.getMaxDifficulty(), attachment.getRuleset(), attachment.getTotalSupply(), 0);
    }

    private Currency(Long currencyId, Long accountId, String name, String code, String description, byte type, long totalSupply, int issuanceHeight, long minReservePerUnitNQT, byte minDifficulty, byte maxDifficulty, byte ruleset, long currentSupply, long currentReservePerUnitNQT) {
        this.currencyId = currencyId;
        this.dbKey = currencyDbKeyFactory.newKey(this.currencyId);
        this.accountId = accountId;
        this.name = name;
        this.code = code;
        this.description = description;
        this.type = type;
        this.totalSupply = totalSupply;
        this.issuanceHeight = issuanceHeight;
        this.minReservePerUnitNQT = minReservePerUnitNQT;
        this.minDifficulty = minDifficulty;
        this.maxDifficulty = maxDifficulty;
        this.ruleset = ruleset;
        this.currentSupply = currentSupply;
        this.currentReservePerUnitNQT = currentReservePerUnitNQT;
    }

    private Currency(ResultSet rs) throws SQLException {
        this.currencyId = rs.getLong("id");
        this.dbKey = currencyDbKeyFactory.newKey(this.currencyId);
        this.accountId = rs.getLong("account_id");
        this.name = rs.getString("name");
        this.code = rs.getString("code");
        this.description = rs.getString("description");
        this.type = rs.getByte("type");
        this.totalSupply = rs.getLong("total_supply");
        this.issuanceHeight = rs.getInt("issuance_height");
        this.minReservePerUnitNQT = rs.getLong("min_reserve_per_unit_nqt");
        this.minDifficulty = rs.getByte("min_difficulty");
        this.maxDifficulty = rs.getByte("max_difficulty");
        this.ruleset = rs.getByte("ruleset");
        this.currentSupply = rs.getLong("current_supply");
        this.currentReservePerUnitNQT = rs.getLong("current_reserve_per_unit_nqt");
    }

    private void save(Connection con) throws SQLException {
        try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO currency (id, account_id, name, code, "
                + "description, type, total_supply, issuance_height, min_reserve_per_unit_nqt, "
                + "min_difficulty, max_difficulty, ruleset, current_supply, current_reserve_per_unit_nqt, height) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int i = 0;
            pstmt.setLong(++i, this.getId());
            pstmt.setLong(++i, this.getAccountId());
            pstmt.setString(++i, this.getName());
            pstmt.setString(++i, this.getCode());
            pstmt.setString(++i, this.getDescription());
            pstmt.setByte(++i, this.getType());
            pstmt.setLong(++i, this.getTotalSupply());
            pstmt.setInt(++i, this.getIssuanceHeight());
            pstmt.setLong(++i, this.getMinReservePerUnitNQT());
            pstmt.setByte(++i, this.getMinDifficulty());
            pstmt.setByte(++i, this.getMaxDifficulty());
            pstmt.setByte(++i, this.getRuleset());
            pstmt.setLong(++i, this.getCurrentSupply());
            pstmt.setLong(++i, this.getCurrentReservePerUnitNQT());
            pstmt.setInt(++i, Nxt.getBlockchain().getHeight());
            pstmt.executeUpdate();
        }
    }

    public Long getId() {
        return currencyId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getName() {
        return name;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public byte getType() {
        return type;
    }

    public long getTotalSupply() {
        return totalSupply;
    }

    public int getIssuanceHeight() {
        return issuanceHeight;
    }

    public long getMinReservePerUnitNQT() {
        return minReservePerUnitNQT;
    }

    public byte getMinDifficulty() {
        return minDifficulty;
    }

    public byte getMaxDifficulty() {
        return maxDifficulty;
    }

    public byte getRuleset() {
        return ruleset;
    }

    public long getCurrentSupply() {
        return currentSupply;
    }

    public long getCurrentReservePerUnitNQT() {
        return currentReservePerUnitNQT;
    }

    public static boolean isIssued(Long currencyId) {
        Currency currency = getCurrency(currencyId);
        return currency != null && currency.getIssuanceHeight() <= BlockchainImpl.getInstance().getLastBlock().getHeight();
    }

    public static void increaseReserve(Account account, Long currencyId, long amountNQT) {
        Currency currency = Currency.getCurrency(currencyId);
        account.addToBalanceNQT(-Convert.safeMultiply(currency.getTotalSupply(), amountNQT));
        currency.currentReservePerUnitNQT += amountNQT;
        currencyTable.insert(currency);
        CurrencyFounder.addFounder(currencyId, account.getId(), Convert.safeMultiply(currency.getTotalSupply(), amountNQT));
    }

    public static void claimReserve(Account account, Long currencyId, long units) {
        account.addToCurrencyBalanceQNT(currencyId, -units);
        Currency currency = Currency.getCurrency(currencyId);
        currency.totalSupply -= units;
        currencyTable.insert(currency);
        account.addToBalanceAndUnconfirmedBalanceNQT(Convert.safeMultiply(units, currency.currentReservePerUnitNQT));
    }

    public static void transferMoney(Account account, List<Attachment.MonetarySystemMoneyTransfer.Entry> entries) {
        for (Attachment.MonetarySystemMoneyTransfer.Entry entry : entries) {
            account.addToCurrencyBalanceQNT(entry.getCurrencyId(), -entry.getUnits());
            Account.addOrGetAccount(entry.getRecipientId()).addToCurrencyAndUnconfirmedCurrencyBalanceQNT(entry.getCurrencyId(), entry.getUnits());
        }
    }

    public void increaseSupply(int units) {
        currentSupply += units;
        currencyTable.insert(this);
    }

}
