package org.tron.core.actuator;

import com.google.common.base.Preconditions;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.utils.StringUtil;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.db.Manager;
import org.tron.core.exception.BalanceInsufficientException;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.protos.Contract.WitnessCreateContract;
import org.tron.protos.Protocol.Transaction.Result.code;

@Slf4j
public class WitnessCreateActuator extends AbstractActuator {

  WitnessCreateActuator(final Any contract, final Manager dbManager) {
    super(contract, dbManager);
  }

  @Override
  public boolean execute(TransactionResultCapsule ret) throws ContractExeException {
    long fee = calcFee();
    try {
      final WitnessCreateContract witnessCreateContract = this.contract
          .unpack(WitnessCreateContract.class);
      this.createWitness(witnessCreateContract);
      ret.setStatus(fee, code.SUCESS);
    } catch (final InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(fee, code.FAILED);
      throw new ContractExeException(e.getMessage());
    }
    return true;
  }

  @Override
  public boolean validate() throws ContractValidateException {
    try {
      if (!this.contract.is(WitnessCreateContract.class)) {
        throw new ContractValidateException(
            "contract type error,expected type [AccountCreateContract],real type[" + this.contract
                .getClass() + "]");
      }

      final WitnessCreateContract contract = this.contract.unpack(WitnessCreateContract.class);
      byte[] ownerAddress = contract.getOwnerAddress().toByteArray();
      String readableOwnerAddress = StringUtil.createReadableString(ownerAddress);

      if (!Wallet.addressValid(ownerAddress)) {
        throw new ContractValidateException("Invalidate address");
      }

      AccountCapsule accountCapsule = this.dbManager.getAccountStore().get(ownerAddress);
      if (accountCapsule == null) {
        throw new ContractValidateException("account[" + readableOwnerAddress + "] not exists");
      }
      long balance = accountCapsule.getBalance();
      Preconditions.checkArgument(balance >= WitnessCapsule.MIN_BALANCE,
          "witnessAccount  has balance["
              + balance + "] < MIN_BALANCE[" + WitnessCapsule.MIN_BALANCE
              + "]");

      Preconditions.checkArgument(
          !this.dbManager.getWitnessStore().has(ownerAddress),
          "Witness[" + readableOwnerAddress + "] has existed");

      Preconditions
          .checkArgument(balance >= dbManager.getDynamicPropertiesStore().getAccountUpgradeCost(),
              "balance < AccountUpgradeCost");

    } catch (final Exception ex) {
      ex.printStackTrace();
      throw new ContractValidateException(ex.getMessage());
    }
    return true;
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return contract.unpack(WitnessCreateContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return 0;
  }

  private void createWitness(final WitnessCreateContract witnessCreateContract) {
    //Create Witness by witnessCreateContract
    final WitnessCapsule witnessCapsule = new WitnessCapsule(
        witnessCreateContract.getOwnerAddress(), 0, witnessCreateContract.getUrl().toStringUtf8());

    logger.debug("createWitness,address[{}]", witnessCapsule.createReadableString());
    this.dbManager.getWitnessStore().put(witnessCapsule.createDbKey(), witnessCapsule);

    try {
      dbManager.adjustBalance(witnessCreateContract.getOwnerAddress().toByteArray(),
          -dbManager.getDynamicPropertiesStore().getAccountUpgradeCost());

      dbManager.adjustBalance(this.dbManager.getAccountStore().getBlackhole().createDbKey(),
          +dbManager.getDynamicPropertiesStore().getAccountUpgradeCost());

    } catch (BalanceInsufficientException e) {
      throw new RuntimeException(e);
    }


  }

}
