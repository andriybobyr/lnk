package nxt;

import nxt.db.DbClause;
import nxt.db.DbIterator;
import nxt.db.DbKey;
import nxt.db.DbUtils;
import nxt.db.EntityDbTable;
import nxt.db.ValuesDbTable;
import nxt.util.Convert;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class PhasingPoll extends AbstractPoll {

    public static final class PhasingPollResult {

        private final long id;
        private final DbKey dbKey;
        private final long result;
        private final boolean approved;

        private PhasingPollResult(PhasingPoll poll, long result) {
            this.id = poll.getId();
            this.dbKey = resultDbKeyFactory.newKey(this.id);
            this.result = result;
            this.approved = result >= poll.getQuorum();
        }

        private PhasingPollResult(ResultSet rs) throws SQLException {
            this.id = rs.getLong("id");
            this.dbKey = resultDbKeyFactory.newKey(this.id);
            this.result = rs.getLong("result");
            this.approved = rs.getBoolean("approved");
        }

        private void save(Connection con) throws SQLException {
            try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO phasing_poll_result (id, "
                    + "result, approved, height) VALUES (?, ?, ?, ?)")) {
                int i = 0;
                pstmt.setLong(++i, id);
                pstmt.setLong(++i, result);
                pstmt.setBoolean(++i, approved);
                pstmt.setInt(++i, Nxt.getBlockchain().getHeight());
                pstmt.executeUpdate();
            }
        }

        public long getId() {
            return id;
        }

        public long getResult() {
            return result;
        }

        public boolean isApproved() {
            return approved;
        }
    }

    private static final DbKey.LongKeyFactory<PhasingPoll> phasingPollDbKeyFactory = new DbKey.LongKeyFactory<PhasingPoll>("id") {
        @Override
        public DbKey newKey(PhasingPoll poll) {
            return poll.dbKey;
        }
    };

    private static final EntityDbTable<PhasingPoll> phasingPollTable = new EntityDbTable<PhasingPoll>("phasing_poll", phasingPollDbKeyFactory) {

        @Override
        protected PhasingPoll load(Connection con, ResultSet rs) throws SQLException {
            return new PhasingPoll(rs);
        }

        @Override
        protected void save(Connection con, PhasingPoll poll) throws SQLException {
            poll.save(con);
        }

        @Override
        public void trim(int height) {
            super.trim(height);
            try (Connection con = Db.db.getConnection();
                 DbIterator<PhasingPoll> pollsToTrim = getFinishingBefore(height);
                 PreparedStatement pstmt1 = con.prepareStatement("DELETE FROM phasing_poll WHERE id = ?");
                 PreparedStatement pstmt2 = con.prepareStatement("DELETE FROM phasing_poll_voter WHERE transaction_id = ?");
                 PreparedStatement pstmt3 = con.prepareStatement("DELETE FROM phasing_vote WHERE transaction_id = ?")) {
                while (pollsToTrim.hasNext()) {
                    PhasingPoll polltoTrim = pollsToTrim.next();
                    long id = polltoTrim.getId();
                    pstmt1.setLong(1, id);
                    pstmt1.executeUpdate();
                    pstmt2.setLong(1, id);
                    pstmt2.executeUpdate();
                    pstmt3.setLong(1, id);
                    pstmt3.executeUpdate();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e.toString(), e);
            } finally {
                clearCache();
            }
        }
    };

    private static final DbKey.LongKeyFactory<PhasingPoll> votersDbKeyFactory = new DbKey.LongKeyFactory<PhasingPoll>("transaction_id") {
        @Override
        public DbKey newKey(PhasingPoll poll) {
            return poll.dbKey;
        }
    };

    private static final ValuesDbTable<PhasingPoll, Long> votersTable = new ValuesDbTable<PhasingPoll, Long>("phasing_poll_voter", votersDbKeyFactory) {

        @Override
        protected Long load(Connection con, ResultSet rs) throws SQLException {
            return rs.getLong("voter_id");
        }

        @Override
        protected void save(Connection con, PhasingPoll poll, Long accountId) throws SQLException {
            try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO phasing_poll_voter (transaction_id, "
                    + "voter_id, height) VALUES (?, ?, ?)")) {
                int i = 0;
                pstmt.setLong(++i, poll.getId());
                pstmt.setLong(++i, accountId);
                pstmt.setInt(++i, Nxt.getBlockchain().getHeight());
                pstmt.executeUpdate();
            }
        }
    };

    private static final DbKey.LongKeyFactory<PhasingPollResult> resultDbKeyFactory = new DbKey.LongKeyFactory<PhasingPollResult>("id") {
        @Override
        public DbKey newKey(PhasingPollResult phasingPollResult) {
            return phasingPollResult.dbKey;
        }
    };

    private static final EntityDbTable<PhasingPollResult> resultTable = new EntityDbTable<PhasingPollResult>("phasing_poll_result", resultDbKeyFactory) {

        @Override
        protected PhasingPollResult load(Connection con, ResultSet rs) throws SQLException {
            return new PhasingPollResult(rs);
        }

        @Override
        protected void save(Connection con, PhasingPollResult phasingPollResult) throws SQLException {
            phasingPollResult.save(con);
        }
    };

    public static PhasingPollResult getResult(long id) {
        return resultTable.get(resultDbKeyFactory.newKey(id));
    }

    public static int getPendingCount() {
        return phasingPollTable.getCount(new DbClause.IntClause("finish_height", DbClause.Op.GT, Nxt.getBlockchain().getHeight()));
    }

    public static DbIterator<PhasingPoll> getFinishingBefore(int height) {
        return phasingPollTable.getManyBy(new DbClause.IntClause("finish_height", DbClause.Op.LT, height), 0, Integer.MAX_VALUE);
    }

    public static PhasingPoll getPoll(long id) {
        return phasingPollTable.get(phasingPollDbKeyFactory.newKey(id));
    }

    public static DbIterator<PhasingPoll> getByAccountId(long accountId, int firstIndex, int lastIndex) {
        DbClause clause = new DbClause.LongClause("account_id", accountId);
        return phasingPollTable.getManyBy(clause, firstIndex, lastIndex);
    }

    public static DbIterator<PhasingPoll> getByHoldingId(long holdingId, int firstIndex, int lastIndex) {
        DbClause clause = new DbClause.LongClause("holding_id", holdingId);
        return phasingPollTable.getManyBy(clause, firstIndex, lastIndex);
    }

    public static DbIterator<TransactionImpl> getFinishingTransactions(int height) {
        Connection con = null;
        try {
            con = Db.db.getConnection();
            PreparedStatement pstmt = con.prepareStatement("SELECT transaction.* FROM transaction, phasing_poll " +
                    "WHERE phasing_poll.id = transaction.id AND phasing_poll.finish_height = ? " +
                    "ORDER BY transaction.height, transaction.transaction_index"); // ASC, not DESC
            pstmt.setInt(1, height);
            return BlockchainImpl.getInstance().getTransactions(con, pstmt);
        } catch (SQLException e) {
            DbUtils.close(con);
            throw new RuntimeException(e.toString(), e);
        }
    }

    public static DbIterator<TransactionImpl> getVoterPendingTransactions(Account voter, int from, int to) {
        Connection con = null;
        try {
            con = Db.db.getConnection();
            PreparedStatement pstmt = con.prepareStatement("SELECT transaction.* "
                    + "FROM transaction, phasing_poll, phasing_poll_voter "
                    + "WHERE transaction.id = phasing_poll.id AND "
                    + "phasing_poll.finish_height > ? AND "
                    + "phasing_poll.id = phasing_poll_voter.transaction_id "
                    + "AND phasing_poll_voter.voter_id = ? "
                    + "ORDER BY transaction.height DESC, transaction.transaction_index DESC "
                    + DbUtils.limitsClause(from, to));
            int i = 0;
            pstmt.setInt(++i, Nxt.getBlockchain().getHeight());
            pstmt.setLong(++i, voter.getId());
            DbUtils.setLimits(++i, pstmt, from, to);

            return BlockchainImpl.getInstance().getTransactions(con, pstmt);
        } catch (SQLException e) {
            DbUtils.close(con);
            throw new RuntimeException(e.toString(), e);
        }
    }

    public static DbIterator<TransactionImpl> getHoldingPendingTransactions(long holdingId, VoteWeighting.VotingModel votingModel,
                                                                            Account account, boolean withoutWhitelist, int from, int to) {

        Connection con = null;
        try {
            con = Db.db.getConnection();
            PreparedStatement pstmt = con.prepareStatement("SELECT transaction.* " +
                    "FROM transaction, phasing_poll " +
                    "WHERE phasing_poll.holding_id = ? " +
                    "AND phasing_poll.voting_model = ? " +
                    "AND phasing_poll.id = transaction.id " +
                    "AND phasing_poll.finish_height > ? " +
                    (account != null ? "AND phasing_poll.account_id = ? " : "") +
                    (withoutWhitelist ? "AND phasing_poll.whitelist_size = 0 " : "") +
                    "ORDER BY transaction.height DESC, transaction.transaction_index DESC " +
                    DbUtils.limitsClause(from, to));
            int i = 0;
            pstmt.setLong(++i, holdingId);
            pstmt.setByte(++i, votingModel.getCode());
            pstmt.setInt(++i, Nxt.getBlockchain().getHeight());
            if (account != null) {
                pstmt.setLong(++i, account.getId());
            }
            DbUtils.setLimits(++i, pstmt, from, to);

            return BlockchainImpl.getInstance().getTransactions(con, pstmt);
        } catch (SQLException e) {
            DbUtils.close(con);
            throw new RuntimeException(e.toString(), e);
        }
    }

    public static DbIterator<TransactionImpl> getAccountPendingTransactions(Account account, int from, int to) {
        Connection con = null;
        try {
            con = Db.db.getConnection();
            PreparedStatement pstmt = con.prepareStatement("SELECT transaction.* FROM transaction, phasing_poll  " +
                    " WHERE transaction.phased = true AND (transaction.sender_id = ? OR transaction.recipient_id = ?) " +
                    " AND phasing_poll.id = transaction.id " +
                    " AND phasing_poll.finish_height > ? ORDER BY transaction.height DESC, transaction.transaction_index DESC " +
                    DbUtils.limitsClause(from, to));
            int i = 0;
            pstmt.setLong(++i, account.getId());
            pstmt.setLong(++i, account.getId());
            pstmt.setInt(++i, Nxt.getBlockchain().getHeight());
            DbUtils.setLimits(++i, pstmt, from, to);

            return BlockchainImpl.getInstance().getTransactions(con, pstmt);
        } catch (SQLException e) {
            DbUtils.close(con);
            throw new RuntimeException(e.toString(), e);
        }
    }

    static void addPoll(Transaction transaction, Appendix.Phasing appendix) {
        PhasingPoll poll = new PhasingPoll(transaction, appendix);
        phasingPollTable.insert(poll);
        long[] voters = poll.getWhitelist();
        if (voters.length > 0) {
            votersTable.insert(poll, Convert.toList(voters));
        }
    }

    static void init() {
    }

    private final DbKey dbKey;
    private final long[] whitelist;
    private final long quorum;
    private final byte[] fullHash;

    private PhasingPoll(Transaction transaction, Appendix.Phasing appendix) {
        super(transaction.getId(), transaction.getSenderId(), appendix.getFinishHeight(), appendix.getVoteWeighting());
        this.dbKey = phasingPollDbKeyFactory.newKey(this.id);
        this.quorum = appendix.getQuorum();
        this.whitelist = appendix.getWhitelist();
        this.fullHash = voteWeighting.getVotingModel() == VoteWeighting.VotingModel.NONE ? null : Convert.parseHexString(transaction.getFullHash());
    }

    private PhasingPoll(ResultSet rs) throws SQLException {
        super(rs);
        this.dbKey = phasingPollDbKeyFactory.newKey(this.id);
        this.quorum = rs.getLong("quorum");
        this.whitelist = rs.getByte("whitelist_size") == 0 ? Convert.EMPTY_LONG : Convert.toArray(votersTable.get(votersDbKeyFactory.newKey(this)));
        this.fullHash = rs.getBytes("full_hash");
    }

    void finish(long result) {
        PhasingPollResult phasingPollResult = new PhasingPollResult(this, result);
        resultTable.insert(phasingPollResult);
    }

    public long[] getWhitelist() {
        return whitelist;
    }

    public long getQuorum() {
        return quorum;
    }

    public byte[] getFullHash() {
        return fullHash;
    }

    private void save(Connection con) throws SQLException {
        try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO phasing_poll (id, account_id, "
                + "finish_height, whitelist_size, voting_model, quorum, min_balance, holding_id, "
                + "min_balance_model, full_hash, height) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int i = 0;
            pstmt.setLong(++i, id);
            pstmt.setLong(++i, accountId);
            pstmt.setInt(++i, finishHeight);
            pstmt.setByte(++i, (byte) whitelist.length);
            pstmt.setByte(++i, voteWeighting.getVotingModel().getCode());
            DbUtils.setLongZeroToNull(pstmt, ++i, quorum);
            DbUtils.setLongZeroToNull(pstmt, ++i, voteWeighting.getMinBalance());
            DbUtils.setLongZeroToNull(pstmt, ++i, voteWeighting.getHoldingId());
            pstmt.setByte(++i, voteWeighting.getMinBalanceModel().getCode());
            DbUtils.setBytes(pstmt, ++i, fullHash);
            pstmt.setInt(++i, Nxt.getBlockchain().getHeight());
            pstmt.executeUpdate();
        }
    }
}