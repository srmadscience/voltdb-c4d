package chargingdemoprocs;

/* This file is part of VoltDB.
 * Copyright (C) 2008-2020 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;
import org.voltdb.types.TimestampType;

public class GetAndLockUser extends VoltProcedure {

  // @formatter:off

    public static final SQLStmt getUser = new SQLStmt("SELECT * FROM user_table WHERE userid = ?;");
    
    public static final SQLStmt getAllTxn = new SQLStmt("SELECT user_txn_id, txn_time "
        + "FROM user_recent_transactions "
        + "WHERE userid = ? ORDER BY txn_time, user_txn_id;");
    
	public static final SQLStmt getUserUsage = new SQLStmt(
			"SELECT * FROM user_usage_table WHERE userid = ? ORDER BY sessionid;");

    public static final SQLStmt upsertUserLock = new SQLStmt("UPDATE user_table "
        + "SET user_softlock_sessionid = ? "
        + "   ,user_softlock_expiry = DATEADD(MILLISECOND,?,?) "
        + "WHERE userid = ?;");

    // @formatter:on

  /**
   * Gets all the information we have about a user, while adding an expiring timestamp
   * and an internally generated lock id that is used to do updates.
   * 
   * @param userId
   * @return lockid (accessible via ClientStatus.getAppStatusString())
   * @throws VoltAbortException
   */
  public VoltTable[] run(long userId) throws VoltAbortException {

    voltQueueSQL(getUser, userId);

    VoltTable[] userRecord = voltExecuteSQL();

    // Sanity check: Does this user exist?
    if (!userRecord[0].advanceRow()) {
      throw new VoltAbortException("User " + userId + " does not exist");
    }

    final TimestampType currentTimestamp = new TimestampType(this.getTransactionTime());
    final TimestampType lockingSessionExpiryTimestamp = userRecord[0].getTimestampAsTimestamp("user_softlock_expiry");

    // If somebody has locked this session and the lock hasn't expired complain...
    if (lockingSessionExpiryTimestamp != null && lockingSessionExpiryTimestamp.compareTo(currentTimestamp) > 0) {

      final long lockingSessionId = userRecord[0].getLong("user_softlock_sessionid");
      this.setAppStatusCode(ReferenceData.STATUS_RECORD_ALREADY_SOFTLOCKED);
      this.setAppStatusString("User " + userId + " has already been locked by session " + lockingSessionId);

    } else {
      // 'Lock' record 
      final long lockingSessionId = getUniqueId();
      this.setAppStatusCode(ReferenceData.STATUS_RECORD_HAS_BEEN_SOFTLOCKED);
      
      //Note how we pass the lock ID back...
      this.setAppStatusString("" + lockingSessionId);
      voltQueueSQL(upsertUserLock, getUniqueId(),  ReferenceData.LOCK_TIMEOUT_MS, currentTimestamp, userId);
    }

    voltQueueSQL(getUser, userId);
    voltQueueSQL(getAllTxn, userId);
    voltQueueSQL(getUserUsage, userId);

    return voltExecuteSQL(true);

  }
}
