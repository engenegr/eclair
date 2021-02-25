/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.Crypto.{ripemd160, sha256}
import fr.acinq.bitcoin.Script.{pay2wpkh, pay2wsh, write}
import fr.acinq.bitcoin.{Btc, ByteVector32, Crypto, MilliBtc, MilliBtcDouble, OutPoint, PimpSatoshi, PrivateKey, Protocol, Satoshi, SatoshiLong, Script, ScriptWitness, Transaction, TxIn, TxOut}
import fr.acinq.bitcoin.SigHash._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.transactions.CommitmentOutput.{InHtlc, OutHtlc}
import fr.acinq.eclair.transactions.Scripts.{anchor, htlcOffered, htlcReceived, toLocalDelayed}
import fr.acinq.eclair.transactions.Transactions.AnchorOutputsCommitmentFormat.anchorAmount
import fr.acinq.eclair.transactions.Transactions.{addSigs, _}
import fr.acinq.eclair.wire.UpdateAddHtlc
import fr.acinq.eclair.{MilliSatoshi, randomBytes32, _}
import KotlinUtils._
import fr.acinq.bitcoin.crypto.Pack
import grizzled.slf4j.Logging
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

import scala.io.Source
import scala.util.Random

/**
 * Created by PM on 16/12/2016.
 */

class TransactionsSpec extends AnyFunSuite with Logging {
  val localFundingPriv = new PrivateKey(randomBytes32)
  val remoteFundingPriv = new PrivateKey(randomBytes32)
  val localRevocationPriv = new PrivateKey(randomBytes32)
  val localPaymentPriv = new PrivateKey(randomBytes32)
  val localDelayedPaymentPriv = new PrivateKey(randomBytes32)
  val remotePaymentPriv = new PrivateKey(randomBytes32)
  val localHtlcPriv = new PrivateKey(randomBytes32)
  val remoteHtlcPriv = new PrivateKey(randomBytes32)
  val finalPubKeyScript = Script.write(Script.pay2wpkh(new PrivateKey(randomBytes32).publicKey))
  val commitInput = Funding.makeFundingInputInfo(randomBytes32, 0, Btc(1), localFundingPriv.publicKey, remoteFundingPriv.publicKey)
  val toLocalDelay = CltvExpiryDelta(144)
  val localDustLimit = new Satoshi(546)
  val feeratePerKw = FeeratePerKw(22000 sat)

  test("extract csv and cltv timeouts") {
    val parentTxId1 = randomBytes32
    val parentTxId2 = randomBytes32
    val parentTxId3 = randomBytes32
    val txIn = Seq(
      new TxIn(new OutPoint(parentTxId1.reversed(), 3), Nil, 3),
      new TxIn(new OutPoint(parentTxId2.reversed(), 1), Nil, 4),
      new TxIn(new OutPoint(parentTxId3.reversed(), 0), Nil, 5),
      new TxIn(new OutPoint(randomBytes32, 4), Nil, 0),
      new TxIn(new OutPoint(parentTxId1.reversed(), 2), Nil, 5),
    )
    val tx = new Transaction(2, txIn, Nil, 10)
    val expected = Map(
      parentTxId1 -> 5,
      parentTxId2 -> 4,
      parentTxId3 -> 5,
    )
    assert(expected === Scripts.csvTimeouts(tx))
    assert(10 === Scripts.cltvTimeout(tx))
  }

  test("encode/decode sequence and locktime (one example)") {
    val txnumber = 0x11F71FB268DL

    val (sequence, locktime) = encodeTxNumber(txnumber)
    assert(sequence == 0x80011F71L)
    assert(locktime == 0x20FB268DL)

    val txnumber1 = decodeTxNumber(sequence, locktime)
    assert(txnumber == txnumber1)
  }

  test("reconstruct txnumber from sequence and locktime") {
    for (_ <- 0 until 1000) {
      val txnumber = Random.nextLong() & 0xffffffffffffL
      val (sequence, locktime) = encodeTxNumber(txnumber)
      val txnumber1 = decodeTxNumber(sequence, locktime)
      assert(txnumber == txnumber1)
    }
  }

  test("compute fees") {
    // see BOLT #3 specs
    val htlcs = Set[DirectedHtlc](
      OutgoingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 5000000 msat, ByteVector32.Zeroes, CltvExpiry(552), TestConstants.emptyOnionPacket)),
      OutgoingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 1000000 msat, ByteVector32.Zeroes, CltvExpiry(553), TestConstants.emptyOnionPacket)),
      IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 7000000 msat, ByteVector32.Zeroes, CltvExpiry(550), TestConstants.emptyOnionPacket)),
      IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 800000 msat, ByteVector32.Zeroes, CltvExpiry(551), TestConstants.emptyOnionPacket))
    )
    val spec = CommitmentSpec(htlcs, feeratePerKw = FeeratePerKw(5000 sat), toLocal = 0 msat, toRemote = 0 msat)
    val fee = Transactions.commitTxFee(546 sat, spec, DefaultCommitmentFormat)
    assert(fee === 5340.sat)
  }

  test("check pre-computed transaction weights") {
    val finalPubKeyScript = ByteVector.view(Script.write(Script.pay2wpkh(new PrivateKey(randomBytes32).publicKey)))
    val localDustLimit = 546 sat
    val toLocalDelay = CltvExpiryDelta(144)
    val feeratePerKw = FeeratePerKw.MinimumFeeratePerKw
    val blockHeight = 400000

    {
      // ClaimP2WPKHOutputTx
      // first we create a fake commitTx tx, containing only the output that will be spent by the ClaimP2WPKHOutputTx
      val pubKeyScript = write(pay2wpkh(localPaymentPriv.publicKey))
      val commitTx = new Transaction(0, Nil, new TxOut(20000 sat, pubKeyScript) :: Nil, 0)
      val Right(claimP2WPKHOutputTx) = makeClaimP2WPKHOutputTx(commitTx, localDustLimit, localPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(claimP2WPKHOutputTx, localPaymentPriv.publicKey, PlaceHolderSig).tx)
      assert(claimP2WPKHOutputWeight == weight)
      assert(claimP2WPKHOutputTx.fee >= claimP2WPKHOutputTx.minRelayFee)
    }
    {
      // ClaimHtlcDelayedTx
      // first we create a fake htlcSuccessOrTimeoutTx tx, containing only the output that will be spent by the ClaimDelayedOutputTx
      val pubKeyScript = write(pay2wsh(toLocalDelayed(localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey)))
      val htlcSuccessOrTimeoutTx = new Transaction(0, Nil, new TxOut(20000 sat, pubKeyScript) :: Nil, 0)
      val Right(claimHtlcDelayedTx) = makeClaimLocalDelayedOutputTx(htlcSuccessOrTimeoutTx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(claimHtlcDelayedTx, PlaceHolderSig).tx)
      assert(claimHtlcDelayedWeight == weight)
      assert(claimHtlcDelayedTx.fee >= claimHtlcDelayedTx.minRelayFee)
    }
    {
      // MainPenaltyTx
      // first we create a fake commitTx tx, containing only the output that will be spent by the MainPenaltyTx
      val pubKeyScript = write(pay2wsh(toLocalDelayed(localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey)))
      val commitTx = new Transaction(0, Nil, new TxOut(20000 sat, pubKeyScript) :: Nil, 0)
      val Right(mainPenaltyTx) = makeMainPenaltyTx(commitTx, localDustLimit, localRevocationPriv.publicKey, finalPubKeyScript, toLocalDelay, localPaymentPriv.publicKey, feeratePerKw)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(mainPenaltyTx, PlaceHolderSig).tx)
      assert(mainPenaltyWeight == weight)
      assert(mainPenaltyTx.fee >= mainPenaltyTx.minRelayFee)
    }
    {
      // HtlcPenaltyTx
      // first we create a fake commitTx tx, containing only the output that will be spent by the ClaimHtlcSuccessTx
      val paymentPreimage = randomBytes32
      val htlc = UpdateAddHtlc(ByteVector32.Zeroes, 0, (20000 * 1000) msat, sha256(paymentPreimage), CltvExpiryDelta(144).toCltvExpiry(blockHeight), TestConstants.emptyOnionPacket)
      val redeemScript = htlcReceived(localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localRevocationPriv.publicKey, ripemd160(htlc.paymentHash), htlc.cltvExpiry, DefaultCommitmentFormat)
      val pubKeyScript = write(pay2wsh(redeemScript))
      val commitTx = new Transaction(0, Nil, new TxOut(htlc.amountMsat.truncateToSatoshi, pubKeyScript) :: Nil, 0)
      val Right(htlcPenaltyTx) = makeHtlcPenaltyTx(commitTx, 0, Script.write(redeemScript), localDustLimit, finalPubKeyScript, feeratePerKw)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(htlcPenaltyTx, PlaceHolderSig, localRevocationPriv.publicKey).tx)
      assert(htlcPenaltyWeight == weight)
      assert(htlcPenaltyTx.fee >= htlcPenaltyTx.minRelayFee)
    }
    {
      // ClaimHtlcSuccessTx
      // first we create a fake commitTx tx, containing only the output that will be spent by the ClaimHtlcSuccessTx
      val paymentPreimage = randomBytes32
      val htlc = UpdateAddHtlc(ByteVector32.Zeroes, 0, (20000 * 1000) msat, sha256(paymentPreimage), CltvExpiryDelta(144).toCltvExpiry(blockHeight), TestConstants.emptyOnionPacket)
      val spec = CommitmentSpec(Set(OutgoingHtlc(htlc)), feeratePerKw, toLocal = 0 msat, toRemote = 0 msat)
      val outputs = makeCommitTxOutputs(localIsFunder = true, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, remotePaymentPriv.publicKey, localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localFundingPriv.publicKey, remoteFundingPriv.publicKey, spec, DefaultCommitmentFormat)
      val pubKeyScript = write(pay2wsh(htlcOffered(localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localRevocationPriv.publicKey, ripemd160(htlc.paymentHash), DefaultCommitmentFormat)))
      val commitTx = new Transaction(0, Nil, new TxOut(htlc.amountMsat.truncateToSatoshi, pubKeyScript) :: Nil, 0)
      val Right(claimHtlcSuccessTx) = makeClaimHtlcSuccessTx(commitTx, outputs, localDustLimit, remoteHtlcPriv.publicKey, localHtlcPriv.publicKey, localRevocationPriv.publicKey, finalPubKeyScript, htlc, feeratePerKw, DefaultCommitmentFormat)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(claimHtlcSuccessTx, PlaceHolderSig, paymentPreimage).tx)
      assert(claimHtlcSuccessWeight == weight)
      assert(claimHtlcSuccessTx.fee >= claimHtlcSuccessTx.minRelayFee)
    }
    {
      // ClaimHtlcTimeoutTx
      // first we create a fake commitTx tx, containing only the output that will be spent by the ClaimHtlcTimeoutTx
      val paymentPreimage = randomBytes32
      val htlc = UpdateAddHtlc(ByteVector32.Zeroes, 0, (20000 * 1000) msat, sha256(paymentPreimage), toLocalDelay.toCltvExpiry(blockHeight), TestConstants.emptyOnionPacket)
      val spec = CommitmentSpec(Set(IncomingHtlc(htlc)), feeratePerKw, toLocal = 0 msat, toRemote = 0 msat)
      val outputs = makeCommitTxOutputs(localIsFunder = true, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, remotePaymentPriv.publicKey, localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localFundingPriv.publicKey, remoteFundingPriv.publicKey, spec, DefaultCommitmentFormat)
      val pubKeyScript = write(pay2wsh(htlcReceived(localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localRevocationPriv.publicKey, ripemd160(htlc.paymentHash), htlc.cltvExpiry, DefaultCommitmentFormat)))
      val commitTx = new Transaction(0, Nil, new TxOut(htlc.amountMsat.truncateToSatoshi, pubKeyScript) :: Nil, 0)
      val Right(claimClaimHtlcTimeoutTx) = makeClaimHtlcTimeoutTx(commitTx, outputs, localDustLimit, remoteHtlcPriv.publicKey, localHtlcPriv.publicKey, localRevocationPriv.publicKey, finalPubKeyScript, htlc, feeratePerKw, DefaultCommitmentFormat)
      // we use dummy signatures to compute the weight
      val weight = Transaction.weight(addSigs(claimClaimHtlcTimeoutTx, PlaceHolderSig).tx)
      assert(claimHtlcTimeoutWeight == weight)
      assert(claimClaimHtlcTimeoutTx.fee >= claimClaimHtlcTimeoutTx.minRelayFee)
    }
    {
      // ClaimAnchorOutputTx
      // first we create a fake commitTx tx, containing only the output that will be spent by the ClaimAnchorOutputTx
      val pubKeyScript = write(pay2wsh(anchor(localFundingPriv.publicKey)))
      val commitTx = new Transaction(0, Nil, new TxOut(anchorAmount, pubKeyScript) :: Nil, 0)
      val Right(claimAnchorOutputTx) = makeClaimAnchorOutputTx(commitTx, localFundingPriv.publicKey)
      assert(claimAnchorOutputTx.tx.txOut.isEmpty)
      // we will always add at least one input and one output to be able to set our desired feerate
      // we use dummy signatures to compute the weight
      val p2wpkhWitness = new ScriptWitness().push(Scripts.der(PlaceHolderSig)).push(PlaceHolderPubKey.value)
      val claimAnchorOutputTxWithFees = claimAnchorOutputTx.copy(tx = claimAnchorOutputTx.tx
        .updateInputs(claimAnchorOutputTx.tx.txIn :+ new TxIn(new OutPoint(randomBytes32, 3), 0).updateWitness(p2wpkhWitness))
        .updateOutputs(Seq(new TxOut(1500 sat, Script.pay2wpkh(randomKey.publicKey))))
      )
      val weight = Transaction.weight(addSigs(claimAnchorOutputTxWithFees, PlaceHolderSig).tx)
      assert(weight === 717)
      assert(weight >= claimAnchorOutputMinWeight)
    }
  }

  test("generate valid commitment with some outputs that don't materialize (default commitment format)") {
    val spec = CommitmentSpec(htlcs = Set.empty, feeratePerKw = feeratePerKw, toLocal = 400.millibtc.toMilliSatoshi, toRemote = 300.millibtc.toMilliSatoshi)
    val commitFee = commitTxFee(localDustLimit, spec, DefaultCommitmentFormat)
    val belowDust = (localDustLimit times 0.9).toMilliSatoshi
    val belowDustWithFee = (localDustLimit plus commitFee times 0.9).toMilliSatoshi

    {
      val toRemoteFundeeBelowDust = spec.copy(toRemote = belowDust)
      val outputs = makeCommitTxOutputs(localIsFunder = true, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, remotePaymentPriv.publicKey, localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localFundingPriv.publicKey, remoteFundingPriv.publicKey, toRemoteFundeeBelowDust, DefaultCommitmentFormat)
      assert(outputs.map(_.commitmentOutput) === Seq(CommitmentOutput.ToLocal))
      assert(outputs.head.output.amount.toMilliSatoshi === toRemoteFundeeBelowDust.toLocal - commitFee)
    }
    {
      val toLocalFunderBelowDust = spec.copy(toLocal = belowDustWithFee)
      val outputs = makeCommitTxOutputs(localIsFunder = true, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, remotePaymentPriv.publicKey, localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localFundingPriv.publicKey, remoteFundingPriv.publicKey, toLocalFunderBelowDust, DefaultCommitmentFormat)
      assert(outputs.map(_.commitmentOutput) === Seq(CommitmentOutput.ToRemote))
      assert(outputs.head.output.amount.toMilliSatoshi === toLocalFunderBelowDust.toRemote)
    }
    {
      val toRemoteFunderBelowDust = spec.copy(toRemote = belowDustWithFee)
      val outputs = makeCommitTxOutputs(localIsFunder = false, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, remotePaymentPriv.publicKey, localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localFundingPriv.publicKey, remoteFundingPriv.publicKey, toRemoteFunderBelowDust, DefaultCommitmentFormat)
      assert(outputs.map(_.commitmentOutput) === Seq(CommitmentOutput.ToLocal))
      assert(outputs.head.output.amount.toMilliSatoshi === toRemoteFunderBelowDust.toLocal)
    }
    {
      val toLocalFundeeBelowDust = spec.copy(toLocal = belowDust)
      val outputs = makeCommitTxOutputs(localIsFunder = false, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, remotePaymentPriv.publicKey, localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localFundingPriv.publicKey, remoteFundingPriv.publicKey, toLocalFundeeBelowDust, DefaultCommitmentFormat)
      assert(outputs.map(_.commitmentOutput) === Seq(CommitmentOutput.ToRemote))
      assert(outputs.head.output.amount.toMilliSatoshi === toLocalFundeeBelowDust.toRemote - commitFee)
    }
    {
      val allBelowDust = spec.copy(toLocal = belowDust, toRemote = belowDust)
      val outputs = makeCommitTxOutputs(localIsFunder = true, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, remotePaymentPriv.publicKey, localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localFundingPriv.publicKey, remoteFundingPriv.publicKey, allBelowDust, DefaultCommitmentFormat)
      assert(outputs.isEmpty)
    }
  }

  test("generate valid commitment and htlc transactions (default commitment format)") {
    val finalPubKeyScript = ByteVector.view(Script.write(Script.pay2wpkh(new PrivateKey(randomBytes32).publicKey)))
    val commitInput = Funding.makeFundingInputInfo(randomBytes32, 0, Btc(1), localFundingPriv.publicKey, remoteFundingPriv.publicKey)

    // htlc1 and htlc2 are regular IN/OUT htlcs
    val paymentPreimage1 = randomBytes32
    val htlc1 = UpdateAddHtlc(ByteVector32.Zeroes, 0, MilliBtc(100).toMilliSatoshi, sha256(paymentPreimage1), CltvExpiry(300), TestConstants.emptyOnionPacket)
    val paymentPreimage2 = randomBytes32
    val htlc2 = UpdateAddHtlc(ByteVector32.Zeroes, 1, MilliBtc(200).toMilliSatoshi, sha256(paymentPreimage2), CltvExpiry(310), TestConstants.emptyOnionPacket)
    // htlc3 and htlc4 are dust IN/OUT htlcs, with an amount large enough to be included in the commit tx, but too small to be claimed at 2nd stage
    val paymentPreimage3 = randomBytes32
    val htlc3 = UpdateAddHtlc(ByteVector32.Zeroes, 2, (localDustLimit plus weight2fee(feeratePerKw, DefaultCommitmentFormat.htlcTimeoutWeight)).toMilliSatoshi, sha256(paymentPreimage3), CltvExpiry(295), TestConstants.emptyOnionPacket)
    val paymentPreimage4 = randomBytes32
    val htlc4 = UpdateAddHtlc(ByteVector32.Zeroes, 3, (localDustLimit plus weight2fee(feeratePerKw, DefaultCommitmentFormat.htlcSuccessWeight)).toMilliSatoshi, sha256(paymentPreimage4), CltvExpiry(300), TestConstants.emptyOnionPacket)
    // htlc5 and htlc6 are dust IN/OUT htlcs
    val htlc5 = UpdateAddHtlc(ByteVector32.Zeroes, 4, (localDustLimit times 0.9).toMilliSatoshi, sha256(randomBytes32), CltvExpiry(295), TestConstants.emptyOnionPacket)
    val htlc6 = UpdateAddHtlc(ByteVector32.Zeroes, 5, (localDustLimit times 0.9).toMilliSatoshi, sha256(randomBytes32), CltvExpiry(305), TestConstants.emptyOnionPacket)
    val spec = CommitmentSpec(
      htlcs = Set(
        OutgoingHtlc(htlc1),
        IncomingHtlc(htlc2),
        OutgoingHtlc(htlc3),
        IncomingHtlc(htlc4),
        OutgoingHtlc(htlc5),
        IncomingHtlc(htlc6)
      ),
      feeratePerKw = feeratePerKw,
      toLocal = 400.millibtc.toMilliSatoshi,
      toRemote = 300.millibtc.toMilliSatoshi)

    val outputs = makeCommitTxOutputs(localIsFunder = true, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, remotePaymentPriv.publicKey, localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localFundingPriv.publicKey, remoteFundingPriv.publicKey, spec, DefaultCommitmentFormat)

    val commitTxNumber = 0x404142434445L
    val commitTx = {
      val txinfo = makeCommitTx(commitInput, commitTxNumber, localPaymentPriv.publicKey, remotePaymentPriv.publicKey, localIsFunder = true, outputs)
      val localSig = Transactions.sign(txinfo, localPaymentPriv, TxOwner.Local, DefaultCommitmentFormat)
      val remoteSig = Transactions.sign(txinfo, remotePaymentPriv, TxOwner.Remote, DefaultCommitmentFormat)
      Transactions.addSigs(txinfo, localFundingPriv.publicKey, remoteFundingPriv.publicKey, localSig, remoteSig)
    }

    {
      assert(getCommitTxNumber(commitTx.tx, isFunder = true, localPaymentPriv.publicKey, remotePaymentPriv.publicKey) == commitTxNumber)
      val hash = ByteVector.view(Crypto.sha256(localPaymentPriv.publicKey.value ++ remotePaymentPriv.publicKey.value))
      val num = Pack.int64BE(hash.takeRight(8).toArray, 0) & 0xffffffffffffL
      val check = ((commitTx.tx.txIn.head.sequence & 0xffffff) << 24) | (commitTx.tx.lockTime & 0xffffff)
      assert((check ^ num) == commitTxNumber)
    }

    val (htlcTimeoutTxs, htlcSuccessTxs) = makeHtlcTxs(commitTx.tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, spec.feeratePerKw, outputs, DefaultCommitmentFormat)
    assert(htlcTimeoutTxs.size == 2) // htlc1 and htlc3
    assert(htlcSuccessTxs.size == 2) // htlc2 and htlc4

    {
      // either party spends local->remote htlc output with htlc timeout tx
      for (htlcTimeoutTx <- htlcTimeoutTxs) {
        val localSig = sign(htlcTimeoutTx, localHtlcPriv, TxOwner.Local, DefaultCommitmentFormat)
        val remoteSig = sign(htlcTimeoutTx, remoteHtlcPriv, TxOwner.Remote, DefaultCommitmentFormat)
        val signed = addSigs(htlcTimeoutTx, localSig, remoteSig, DefaultCommitmentFormat)
        assert(checkSpendable(signed).isSuccess)
      }
    }
    {
      // local spends delayed output of htlc1 timeout tx
      val Right(claimHtlcDelayed) = makeClaimLocalDelayedOutputTx(htlcTimeoutTxs(1).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      val localSig = sign(claimHtlcDelayed, localDelayedPaymentPriv, TxOwner.Local, DefaultCommitmentFormat)
      val signedTx = addSigs(claimHtlcDelayed, localSig)
      assert(checkSpendable(signedTx).isSuccess)
      // local can't claim delayed output of htlc3 timeout tx because it is below the dust limit
      val claimHtlcDelayed1 = makeClaimLocalDelayedOutputTx(htlcTimeoutTxs(0).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      assert(claimHtlcDelayed1 === Left(OutputNotFound))
    }
    {
      // remote spends local->remote htlc1/htlc3 output directly in case of success
      for ((htlc, paymentPreimage) <- (htlc1, paymentPreimage1) :: (htlc3, paymentPreimage3) :: Nil) {
        val Right(claimHtlcSuccessTx) = makeClaimHtlcSuccessTx(commitTx.tx, outputs, localDustLimit, remoteHtlcPriv.publicKey, localHtlcPriv.publicKey, localRevocationPriv.publicKey, finalPubKeyScript, htlc, feeratePerKw, DefaultCommitmentFormat)
        val localSig = sign(claimHtlcSuccessTx, remoteHtlcPriv, TxOwner.Local, DefaultCommitmentFormat)
        val signed = addSigs(claimHtlcSuccessTx, localSig, paymentPreimage)
        assert(checkSpendable(signed).isSuccess)
      }
    }
    {
      // local spends remote->local htlc2/htlc4 output with htlc success tx using payment preimage
      for ((htlcSuccessTx, paymentPreimage) <- (htlcSuccessTxs(1), paymentPreimage2) :: (htlcSuccessTxs(0), paymentPreimage4) :: Nil) {
        val localSig = sign(htlcSuccessTx, localHtlcPriv, TxOwner.Local, DefaultCommitmentFormat)
        val remoteSig = sign(htlcSuccessTx, remoteHtlcPriv, TxOwner.Remote, DefaultCommitmentFormat)
        val signedTx = addSigs(htlcSuccessTx, localSig, remoteSig, paymentPreimage, DefaultCommitmentFormat)
        assert(checkSpendable(signedTx).isSuccess)
        // check remote sig
        assert(checkSig(htlcSuccessTx, remoteSig, remoteHtlcPriv.publicKey, TxOwner.Remote, DefaultCommitmentFormat))
      }
    }
    {
      // local spends delayed output of htlc2 success tx
      val Right(claimHtlcDelayed) = makeClaimLocalDelayedOutputTx(htlcSuccessTxs(1).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      val localSig = sign(claimHtlcDelayed, localDelayedPaymentPriv, TxOwner.Local, DefaultCommitmentFormat)
      val signedTx = addSigs(claimHtlcDelayed, localSig)
      assert(checkSpendable(signedTx).isSuccess)
      // local can't claim delayed output of htlc4 success tx because it is below the dust limit
      val claimHtlcDelayed1 = makeClaimLocalDelayedOutputTx(htlcSuccessTxs(0).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      assert(claimHtlcDelayed1 === Left(AmountBelowDustLimit))
    }
    {
      // local spends main delayed output
      val Right(claimMainOutputTx) = makeClaimLocalDelayedOutputTx(commitTx.tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      val localSig = sign(claimMainOutputTx, localDelayedPaymentPriv, TxOwner.Local, DefaultCommitmentFormat)
      val signedTx = addSigs(claimMainOutputTx, localSig)
      assert(checkSpendable(signedTx).isSuccess)
    }
    {
      // remote spends main output
      val Right(claimP2WPKHOutputTx) = makeClaimP2WPKHOutputTx(commitTx.tx, localDustLimit, remotePaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      val localSig = sign(claimP2WPKHOutputTx, remotePaymentPriv, TxOwner.Local, DefaultCommitmentFormat)
      val signedTx = addSigs(claimP2WPKHOutputTx, remotePaymentPriv.publicKey, localSig)
      assert(checkSpendable(signedTx).isSuccess)
    }
    {
      // remote spends remote->local htlc output directly in case of timeout
      val Right(claimHtlcTimeoutTx) = makeClaimHtlcTimeoutTx(commitTx.tx, outputs, localDustLimit, remoteHtlcPriv.publicKey, localHtlcPriv.publicKey, localRevocationPriv.publicKey, finalPubKeyScript, htlc2, feeratePerKw, DefaultCommitmentFormat)
      val localSig = sign(claimHtlcTimeoutTx, remoteHtlcPriv, TxOwner.Local, DefaultCommitmentFormat)
      val signed = addSigs(claimHtlcTimeoutTx, localSig)
      assert(checkSpendable(signed).isSuccess)
    }
    {
      // remote spends local main delayed output with revocation key
      val Right(mainPenaltyTx) = makeMainPenaltyTx(commitTx.tx, localDustLimit, localRevocationPriv.publicKey, finalPubKeyScript, toLocalDelay, localDelayedPaymentPriv.publicKey, feeratePerKw)
      val sig = sign(mainPenaltyTx, localRevocationPriv, TxOwner.Local, DefaultCommitmentFormat)
      val signed = addSigs(mainPenaltyTx, sig)
      assert(checkSpendable(signed).isSuccess)
    }
    {
      // remote spends htlc1's htlc-timeout tx with revocation key
      val Right(claimHtlcDelayedPenaltyTx) = makeClaimHtlcDelayedOutputPenaltyTx(htlcTimeoutTxs(1).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      val sig = sign(claimHtlcDelayedPenaltyTx, localRevocationPriv, TxOwner.Local, DefaultCommitmentFormat)
      val signed = addSigs(claimHtlcDelayedPenaltyTx, sig)
      assert(checkSpendable(signed).isSuccess)
      // remote can't claim revoked output of htlc3's htlc-timeout tx because it is below the dust limit
      val claimHtlcDelayedPenaltyTx1 = makeClaimHtlcDelayedOutputPenaltyTx(htlcTimeoutTxs(0).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      assert(claimHtlcDelayedPenaltyTx1 === Left(AmountBelowDustLimit))
    }
    {
      // remote spends offered HTLC output with revocation key
      val script = Script.write(Scripts.htlcOffered(localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localRevocationPriv.publicKey, Crypto.ripemd160(htlc1.paymentHash), DefaultCommitmentFormat))
      val Some(htlcOutputIndex) = outputs.zipWithIndex.find {
        case (CommitmentOutputLink(_, _, OutHtlc(OutgoingHtlc(someHtlc))), _) => someHtlc.id == htlc1.id
        case _ => false
      }.map(_._2)
      val Right(htlcPenaltyTx) = makeHtlcPenaltyTx(commitTx.tx, htlcOutputIndex, script, localDustLimit, finalPubKeyScript, feeratePerKw)
      val sig = sign(htlcPenaltyTx, localRevocationPriv, TxOwner.Local, DefaultCommitmentFormat)
      val signed = addSigs(htlcPenaltyTx, sig, localRevocationPriv.publicKey)
      assert(checkSpendable(signed).isSuccess)
    }
    {
      // remote spends htlc2's htlc-success tx with revocation key
      val Right(claimHtlcDelayedPenaltyTx) = makeClaimHtlcDelayedOutputPenaltyTx(htlcSuccessTxs(1).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      val sig = sign(claimHtlcDelayedPenaltyTx, localRevocationPriv, TxOwner.Local, DefaultCommitmentFormat)
      val signed = addSigs(claimHtlcDelayedPenaltyTx, sig)
      assert(checkSpendable(signed).isSuccess)
      // remote can't claim revoked output of htlc4's htlc-success tx because it is below the dust limit
      val claimHtlcDelayedPenaltyTx1 = makeClaimHtlcDelayedOutputPenaltyTx(htlcSuccessTxs(0).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      assert(claimHtlcDelayedPenaltyTx1 === Left(AmountBelowDustLimit))
    }
    {
      // remote spends received HTLC output with revocation key
      val script = Script.write(Scripts.htlcReceived(localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localRevocationPriv.publicKey, Crypto.ripemd160(htlc2.paymentHash), htlc2.cltvExpiry, DefaultCommitmentFormat))
      val Some(htlcOutputIndex) = outputs.zipWithIndex.find {
        case (CommitmentOutputLink(_, _, InHtlc(IncomingHtlc(someHtlc))), _) => someHtlc.id == htlc2.id
        case _ => false
      }.map(_._2)
      val Right(htlcPenaltyTx) = makeHtlcPenaltyTx(commitTx.tx, htlcOutputIndex, script, localDustLimit, finalPubKeyScript, feeratePerKw)
      val sig = sign(htlcPenaltyTx, localRevocationPriv, TxOwner.Local, DefaultCommitmentFormat)
      val signed = addSigs(htlcPenaltyTx, sig, localRevocationPriv.publicKey)
      assert(checkSpendable(signed).isSuccess)
    }
  }

  test("generate valid commitment with some outputs that don't materialize (anchor outputs)") {
    val spec = CommitmentSpec(htlcs = Set.empty, feeratePerKw = feeratePerKw, toLocal = 400.millibtc.toMilliSatoshi, toRemote = 300.millibtc.toMilliSatoshi)
    val commitFee = commitTxFee(localDustLimit, spec, AnchorOutputsCommitmentFormat)
    val belowDust = (localDustLimit times 0.9).toMilliSatoshi
    val belowDustWithFeeAndAnchors = (localDustLimit plus commitFee times 0.9).toMilliSatoshi

    {
      val outputs = makeCommitTxOutputs(localIsFunder = true, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, remotePaymentPriv.publicKey, localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localFundingPriv.publicKey, remoteFundingPriv.publicKey, spec, AnchorOutputsCommitmentFormat)
      assert(outputs.map(_.commitmentOutput).toSet === Set(CommitmentOutput.ToLocal, CommitmentOutput.ToRemote, CommitmentOutput.ToLocalAnchor, CommitmentOutput.ToRemoteAnchor))
      assert(outputs.find(_.commitmentOutput == CommitmentOutput.ToLocalAnchor).get.output.amount === anchorAmount)
      assert(outputs.find(_.commitmentOutput == CommitmentOutput.ToRemoteAnchor).get.output.amount === anchorAmount)
      assert(outputs.find(_.commitmentOutput == CommitmentOutput.ToLocal).get.output.amount.toMilliSatoshi === spec.toLocal - commitFee)
      assert(outputs.find(_.commitmentOutput == CommitmentOutput.ToRemote).get.output.amount.toMilliSatoshi === spec.toRemote)
    }
    {
      val toRemoteFundeeBelowDust = spec.copy(toRemote = belowDust)
      val outputs = makeCommitTxOutputs(localIsFunder = true, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, remotePaymentPriv.publicKey, localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localFundingPriv.publicKey, remoteFundingPriv.publicKey, toRemoteFundeeBelowDust, AnchorOutputsCommitmentFormat)
      assert(outputs.map(_.commitmentOutput).toSet === Set(CommitmentOutput.ToLocal, CommitmentOutput.ToLocalAnchor))
      assert(outputs.find(_.commitmentOutput == CommitmentOutput.ToLocalAnchor).get.output.amount === anchorAmount)
      assert(outputs.find(_.commitmentOutput == CommitmentOutput.ToLocal).get.output.amount.toMilliSatoshi === spec.toLocal - commitFee)
    }
    {
      val toLocalFunderBelowDust = spec.copy(toLocal = belowDustWithFeeAndAnchors)
      val outputs = makeCommitTxOutputs(localIsFunder = true, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, remotePaymentPriv.publicKey, localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localFundingPriv.publicKey, remoteFundingPriv.publicKey, toLocalFunderBelowDust, AnchorOutputsCommitmentFormat)
      assert(outputs.map(_.commitmentOutput).toSet === Set(CommitmentOutput.ToRemote, CommitmentOutput.ToRemoteAnchor))
      assert(outputs.find(_.commitmentOutput == CommitmentOutput.ToRemoteAnchor).get.output.amount === anchorAmount)
      assert(outputs.find(_.commitmentOutput == CommitmentOutput.ToRemote).get.output.amount.toMilliSatoshi === spec.toRemote)
    }
    {
      val toRemoteFunderBelowDust = spec.copy(toRemote = belowDustWithFeeAndAnchors)
      val outputs = makeCommitTxOutputs(localIsFunder = false, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, remotePaymentPriv.publicKey, localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localFundingPriv.publicKey, remoteFundingPriv.publicKey, toRemoteFunderBelowDust, AnchorOutputsCommitmentFormat)
      assert(outputs.map(_.commitmentOutput).toSet === Set(CommitmentOutput.ToLocal, CommitmentOutput.ToLocalAnchor))
      assert(outputs.find(_.commitmentOutput == CommitmentOutput.ToLocalAnchor).get.output.amount === anchorAmount)
      assert(outputs.find(_.commitmentOutput == CommitmentOutput.ToLocal).get.output.amount.toMilliSatoshi === spec.toLocal)
    }
    {
      val toLocalFundeeBelowDust = spec.copy(toLocal = belowDust)
      val outputs = makeCommitTxOutputs(localIsFunder = false, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, remotePaymentPriv.publicKey, localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localFundingPriv.publicKey, remoteFundingPriv.publicKey, toLocalFundeeBelowDust, AnchorOutputsCommitmentFormat)
      assert(outputs.map(_.commitmentOutput).toSet === Set(CommitmentOutput.ToRemote, CommitmentOutput.ToRemoteAnchor))
      assert(outputs.find(_.commitmentOutput == CommitmentOutput.ToRemoteAnchor).get.output.amount === anchorAmount)
      assert(outputs.find(_.commitmentOutput == CommitmentOutput.ToRemote).get.output.amount.toMilliSatoshi === spec.toRemote - commitFee)
    }
    {
      val allBelowDust = spec.copy(toLocal = belowDust, toRemote = belowDust)
      val outputs = makeCommitTxOutputs(localIsFunder = true, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, remotePaymentPriv.publicKey, localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localFundingPriv.publicKey, remoteFundingPriv.publicKey, allBelowDust, AnchorOutputsCommitmentFormat)
      assert(outputs.isEmpty)
    }
  }

  test("generate valid commitment and htlc transactions (anchor outputs)") {
    val finalPubKeyScript = ByteVector.view(Script.write(Script.pay2wpkh(new PrivateKey(randomBytes32).publicKey)))
    val commitInput = Funding.makeFundingInputInfo(randomBytes32, 0, Btc(1), localFundingPriv.publicKey, remoteFundingPriv.publicKey)

    // htlc1, htlc2a and htlc2b are regular IN/OUT htlcs
    val paymentPreimage1 = randomBytes32
    val htlc1 = UpdateAddHtlc(ByteVector32.Zeroes, 0, MilliBtc(100).toMilliSatoshi, sha256(paymentPreimage1), CltvExpiry(300), TestConstants.emptyOnionPacket)
    val paymentPreimage2 = randomBytes32
    val htlc2a = UpdateAddHtlc(ByteVector32.Zeroes, 1, MilliBtc(50).toMilliSatoshi, sha256(paymentPreimage2), CltvExpiry(310), TestConstants.emptyOnionPacket)
    val htlc2b = UpdateAddHtlc(ByteVector32.Zeroes, 2, MilliBtc(150).toMilliSatoshi, sha256(paymentPreimage2), CltvExpiry(310), TestConstants.emptyOnionPacket)
    // htlc3 and htlc4 are dust IN/OUT htlcs, with an amount large enough to be included in the commit tx, but too small to be claimed at 2nd stage
    val paymentPreimage3 = randomBytes32
    val htlc3 = UpdateAddHtlc(ByteVector32.Zeroes, 3, (localDustLimit plus weight2fee(feeratePerKw, AnchorOutputsCommitmentFormat.htlcTimeoutWeight)).toMilliSatoshi, sha256(paymentPreimage3), CltvExpiry(295), TestConstants.emptyOnionPacket)
    val paymentPreimage4 = randomBytes32
    val htlc4 = UpdateAddHtlc(ByteVector32.Zeroes, 4, (localDustLimit plus weight2fee(feeratePerKw, AnchorOutputsCommitmentFormat.htlcSuccessWeight)).toMilliSatoshi, sha256(paymentPreimage4), CltvExpiry(300), TestConstants.emptyOnionPacket)
    // htlc5 and htlc6 are dust IN/OUT htlcs
    val htlc5 = UpdateAddHtlc(ByteVector32.Zeroes, 5, (localDustLimit times 0.9).toMilliSatoshi, sha256(randomBytes32), CltvExpiry(295), TestConstants.emptyOnionPacket)
    val htlc6 = UpdateAddHtlc(ByteVector32.Zeroes, 6, (localDustLimit times 0.9).toMilliSatoshi, sha256(randomBytes32), CltvExpiry(305), TestConstants.emptyOnionPacket)
    val spec = CommitmentSpec(
      htlcs = Set(
        OutgoingHtlc(htlc1),
        IncomingHtlc(htlc2a),
        IncomingHtlc(htlc2b),
        OutgoingHtlc(htlc3),
        IncomingHtlc(htlc4),
        OutgoingHtlc(htlc5),
        IncomingHtlc(htlc6)
      ),
      feeratePerKw = feeratePerKw,
      toLocal = 400.millibtc.toMilliSatoshi,
      toRemote = 300.millibtc.toMilliSatoshi)

    val (commitTx, commitTxOutputs, htlcTimeoutTxs, htlcSuccessTxs) = {
      val commitTxNumber = 0x404142434445L
      val outputs = makeCommitTxOutputs(localIsFunder = true, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, remotePaymentPriv.publicKey, localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localFundingPriv.publicKey, remoteFundingPriv.publicKey, spec, AnchorOutputsCommitmentFormat)
      val txinfo = makeCommitTx(commitInput, commitTxNumber, localPaymentPriv.publicKey, remotePaymentPriv.publicKey, localIsFunder = true, outputs)
      val localSig = Transactions.sign(txinfo, localPaymentPriv, TxOwner.Local, AnchorOutputsCommitmentFormat)
      val remoteSig = Transactions.sign(txinfo, remotePaymentPriv, TxOwner.Remote, AnchorOutputsCommitmentFormat)
      val commitTx = Transactions.addSigs(txinfo, localFundingPriv.publicKey, remoteFundingPriv.publicKey, localSig, remoteSig)
      val (htlcTimeoutTxs, htlcSuccessTxs) = makeHtlcTxs(commitTx.tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, spec.feeratePerKw, outputs, AnchorOutputsCommitmentFormat)
      assert(htlcTimeoutTxs.size == 2) // htlc1 and htlc3
      assert(htlcSuccessTxs.size == 3) // htlc2a, htlc2b and htlc4
      (commitTx, outputs, htlcTimeoutTxs, htlcSuccessTxs)
    }

    {
      // local spends main delayed output
      val Right(claimMainOutputTx) = makeClaimLocalDelayedOutputTx(commitTx.tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      val localSig = sign(claimMainOutputTx, localDelayedPaymentPriv, TxOwner.Local, AnchorOutputsCommitmentFormat)
      val signedTx = addSigs(claimMainOutputTx, localSig)
      assert(checkSpendable(signedTx).isSuccess)
    }
    {
      // remote cannot spend main output with default commitment format
      val Left(failure) = makeClaimP2WPKHOutputTx(commitTx.tx, localDustLimit, remotePaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      assert(failure === OutputNotFound)
    }
    {
      // remote spends main delayed output
      val Right(claimRemoteDelayedOutputTx) = makeClaimRemoteDelayedOutputTx(commitTx.tx, localDustLimit, remotePaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      val localSig = sign(claimRemoteDelayedOutputTx, remotePaymentPriv, TxOwner.Local, AnchorOutputsCommitmentFormat)
      val signedTx = addSigs(claimRemoteDelayedOutputTx, localSig)
      assert(checkSpendable(signedTx).isSuccess)
    }
    {
      // local spends local anchor
      val Right(claimAnchorOutputTx) = makeClaimAnchorOutputTx(commitTx.tx, localFundingPriv.publicKey)
      assert(checkSpendable(claimAnchorOutputTx).isFailure)
      val localSig = sign(claimAnchorOutputTx, localFundingPriv, TxOwner.Local, AnchorOutputsCommitmentFormat)
      val signedTx = addSigs(claimAnchorOutputTx, localSig)
      assert(checkSpendable(signedTx).isSuccess)
    }
    {
      // remote spends remote anchor
      val Right(claimAnchorOutputTx) = makeClaimAnchorOutputTx(commitTx.tx, remoteFundingPriv.publicKey)
      assert(checkSpendable(claimAnchorOutputTx).isFailure)
      val localSig = sign(claimAnchorOutputTx, remoteFundingPriv, TxOwner.Local, AnchorOutputsCommitmentFormat)
      val signedTx = addSigs(claimAnchorOutputTx, localSig)
      assert(checkSpendable(signedTx).isSuccess)
    }
    {
      // remote spends local main delayed output with revocation key
      val Right(mainPenaltyTx) = makeMainPenaltyTx(commitTx.tx, localDustLimit, localRevocationPriv.publicKey, finalPubKeyScript, toLocalDelay, localDelayedPaymentPriv.publicKey, feeratePerKw)
      val sig = sign(mainPenaltyTx, localRevocationPriv, TxOwner.Local, AnchorOutputsCommitmentFormat)
      val signed = addSigs(mainPenaltyTx, sig)
      assert(checkSpendable(signed).isSuccess)
    }
    {
      // local spends received htlc with HTLC-timeout tx
      for (htlcTimeoutTx <- htlcTimeoutTxs) {
        val localSig = sign(htlcTimeoutTx, localHtlcPriv, TxOwner.Local, AnchorOutputsCommitmentFormat)
        val remoteSig = sign(htlcTimeoutTx, remoteHtlcPriv, TxOwner.Remote, AnchorOutputsCommitmentFormat)
        val signedTx = addSigs(htlcTimeoutTx, localSig, remoteSig, AnchorOutputsCommitmentFormat)
        assert(checkSpendable(signedTx).isSuccess)
        // local detects when remote doesn't use the right sighash flags
        val invalidSighash = Seq(SIGHASH_ALL, SIGHASH_ALL | SIGHASH_ANYONECANPAY, SIGHASH_SINGLE, SIGHASH_NONE)
        for (sighash <- invalidSighash) {
          val invalidRemoteSig = sign(htlcTimeoutTx, remoteHtlcPriv, sighash)
          val invalidTx = addSigs(htlcTimeoutTx, localSig, invalidRemoteSig, AnchorOutputsCommitmentFormat)
          assert(checkSpendable(invalidTx).isFailure)
        }
      }
    }
    {
      // local spends delayed output of htlc1 timeout tx
      val Right(claimHtlcDelayed) = makeClaimLocalDelayedOutputTx(htlcTimeoutTxs(1).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      val localSig = sign(claimHtlcDelayed, localDelayedPaymentPriv, TxOwner.Local, AnchorOutputsCommitmentFormat)
      val signedTx = addSigs(claimHtlcDelayed, localSig)
      assert(checkSpendable(signedTx).isSuccess)
      // local can't claim delayed output of htlc3 timeout tx because it is below the dust limit
      val claimHtlcDelayed1 = makeClaimLocalDelayedOutputTx(htlcTimeoutTxs(0).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      assert(claimHtlcDelayed1 === Left(OutputNotFound))
    }
    {
      // local spends offered htlc with HTLC-success tx
      for ((htlcSuccessTx, paymentPreimage) <- (htlcSuccessTxs(0), paymentPreimage4) :: (htlcSuccessTxs(1), paymentPreimage2) :: (htlcSuccessTxs(2), paymentPreimage2) :: Nil) {
        val localSig = sign(htlcSuccessTx, localHtlcPriv, TxOwner.Local, AnchorOutputsCommitmentFormat)
        val remoteSig = sign(htlcSuccessTx, remoteHtlcPriv, TxOwner.Remote, AnchorOutputsCommitmentFormat)
        val signedTx = addSigs(htlcSuccessTx, localSig, remoteSig, paymentPreimage, AnchorOutputsCommitmentFormat)
        assert(checkSpendable(signedTx).isSuccess)
        // check remote sig
        assert(checkSig(htlcSuccessTx, remoteSig, remoteHtlcPriv.publicKey, TxOwner.Remote, AnchorOutputsCommitmentFormat))
        // local detects when remote doesn't use the right sighash flags
        val invalidSighash = Seq(SIGHASH_ALL, SIGHASH_ALL | SIGHASH_ANYONECANPAY, SIGHASH_SINGLE, SIGHASH_NONE)
        for (sighash <- invalidSighash) {
          val invalidRemoteSig = sign(htlcSuccessTx, remoteHtlcPriv, sighash)
          val invalidTx = addSigs(htlcSuccessTx, localSig, invalidRemoteSig, paymentPreimage, AnchorOutputsCommitmentFormat)
          assert(checkSpendable(invalidTx).isFailure)
          assert(!checkSig(invalidTx, invalidRemoteSig, remoteHtlcPriv.publicKey, TxOwner.Remote, AnchorOutputsCommitmentFormat))
        }
      }
    }
    {
      // local spends delayed output of htlc2a and htlc2b success txs
      val Right(claimHtlcDelayedA) = makeClaimLocalDelayedOutputTx(htlcSuccessTxs(1).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      val Right(claimHtlcDelayedB) = makeClaimLocalDelayedOutputTx(htlcSuccessTxs(2).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      for (claimHtlcDelayed <- Seq(claimHtlcDelayedA, claimHtlcDelayedB)) {
        val localSig = sign(claimHtlcDelayed, localDelayedPaymentPriv, TxOwner.Local, AnchorOutputsCommitmentFormat)
        val signedTx = addSigs(claimHtlcDelayed, localSig)
        assert(checkSpendable(signedTx).isSuccess)
      }
      // local can't claim delayed output of htlc4 success tx because it is below the dust limit
      val claimHtlcDelayed1 = makeClaimLocalDelayedOutputTx(htlcSuccessTxs(0).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      assert(claimHtlcDelayed1 === Left(AmountBelowDustLimit))
    }
    {
      // remote spends local->remote htlc outputs directly in case of success
      for ((htlc, paymentPreimage) <- (htlc1, paymentPreimage1) :: (htlc3, paymentPreimage3) :: Nil) {
        val Right(claimHtlcSuccessTx) = makeClaimHtlcSuccessTx(commitTx.tx, commitTxOutputs, localDustLimit, remoteHtlcPriv.publicKey, localHtlcPriv.publicKey, localRevocationPriv.publicKey, finalPubKeyScript, htlc, feeratePerKw, AnchorOutputsCommitmentFormat)
        val localSig = sign(claimHtlcSuccessTx, remoteHtlcPriv, TxOwner.Local, AnchorOutputsCommitmentFormat)
        val signed = addSigs(claimHtlcSuccessTx, localSig, paymentPreimage)
        assert(checkSpendable(signed).isSuccess)
      }
    }
    {
      // remote spends htlc1's htlc-timeout tx with revocation key
      val Right(claimHtlcDelayedPenaltyTx) = makeClaimHtlcDelayedOutputPenaltyTx(htlcTimeoutTxs(1).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      val sig = sign(claimHtlcDelayedPenaltyTx, localRevocationPriv, TxOwner.Local, AnchorOutputsCommitmentFormat)
      val signed = addSigs(claimHtlcDelayedPenaltyTx, sig)
      assert(checkSpendable(signed).isSuccess)
      // remote can't claim revoked output of htlc3's htlc-timeout tx because it is below the dust limit
      val claimHtlcDelayedPenaltyTx1 = makeClaimHtlcDelayedOutputPenaltyTx(htlcTimeoutTxs(0).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      assert(claimHtlcDelayedPenaltyTx1 === Left(AmountBelowDustLimit))
    }
    {
      // remote spends remote->local htlc output directly in case of timeout
      for (htlc <- Seq(htlc2a, htlc2b)) {
        val Right(claimHtlcTimeoutTx) = makeClaimHtlcTimeoutTx(commitTx.tx, commitTxOutputs, localDustLimit, remoteHtlcPriv.publicKey, localHtlcPriv.publicKey, localRevocationPriv.publicKey, finalPubKeyScript, htlc, feeratePerKw, AnchorOutputsCommitmentFormat)
        val localSig = sign(claimHtlcTimeoutTx, remoteHtlcPriv, TxOwner.Local, AnchorOutputsCommitmentFormat)
        val signed = addSigs(claimHtlcTimeoutTx, localSig)
        assert(checkSpendable(signed).isSuccess)
      }
    }
    {
      // remote spends htlc2a/htlc2b's htlc-success tx with revocation key
      val Right(claimHtlcDelayedPenaltyTxA) = makeClaimHtlcDelayedOutputPenaltyTx(htlcSuccessTxs(1).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      val Right(claimHtlcDelayedPenaltyTxB) = makeClaimHtlcDelayedOutputPenaltyTx(htlcSuccessTxs(2).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      for (claimHtlcSuccessPenaltyTx <- Seq(claimHtlcDelayedPenaltyTxA, claimHtlcDelayedPenaltyTxB)) {
        val sig = sign(claimHtlcSuccessPenaltyTx, localRevocationPriv, TxOwner.Local, AnchorOutputsCommitmentFormat)
        val signed = addSigs(claimHtlcSuccessPenaltyTx, sig)
        assert(checkSpendable(signed).isSuccess)
      }
      // remote can't claim revoked output of htlc4's htlc-success tx because it is below the dust limit
      val claimHtlcDelayedPenaltyTx1 = makeClaimHtlcDelayedOutputPenaltyTx(htlcSuccessTxs(0).tx, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, finalPubKeyScript, feeratePerKw)
      assert(claimHtlcDelayedPenaltyTx1 === Left(AmountBelowDustLimit))
    }
    {
      // remote spends offered htlc output with revocation key
      val script = Script.write(Scripts.htlcOffered(localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localRevocationPriv.publicKey, Crypto.ripemd160(htlc1.paymentHash), AnchorOutputsCommitmentFormat))
      val Some(htlcOutputIndex) = commitTxOutputs.zipWithIndex.find {
        case (CommitmentOutputLink(_, _, OutHtlc(OutgoingHtlc(someHtlc))), _) => someHtlc.id == htlc1.id
        case _ => false
      }.map(_._2)
      val Right(htlcPenaltyTx) = makeHtlcPenaltyTx(commitTx.tx, htlcOutputIndex, script, localDustLimit, finalPubKeyScript, feeratePerKw)
      val sig = sign(htlcPenaltyTx, localRevocationPriv, TxOwner.Local, AnchorOutputsCommitmentFormat)
      val signed = addSigs(htlcPenaltyTx, sig, localRevocationPriv.publicKey)
      assert(checkSpendable(signed).isSuccess)
    }
    {
      // remote spends received htlc output with revocation key
      for (htlc <- Seq(htlc2a, htlc2b)) {
        val script = Script.write(Scripts.htlcReceived(localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localRevocationPriv.publicKey, Crypto.ripemd160(htlc.paymentHash), htlc.cltvExpiry, AnchorOutputsCommitmentFormat))
        val Some(htlcOutputIndex) = commitTxOutputs.zipWithIndex.find {
          case (CommitmentOutputLink(_, _, InHtlc(IncomingHtlc(someHtlc))), _) => someHtlc.id == htlc.id
          case _ => false
        }.map(_._2)
        val Right(htlcPenaltyTx) = makeHtlcPenaltyTx(commitTx.tx, htlcOutputIndex, script, localDustLimit, finalPubKeyScript, feeratePerKw)
        val sig = sign(htlcPenaltyTx, localRevocationPriv, TxOwner.Local, AnchorOutputsCommitmentFormat)
        val signed = addSigs(htlcPenaltyTx, sig, localRevocationPriv.publicKey)
        assert(checkSpendable(signed).isSuccess)
      }
    }
  }

  test("sort the htlc outputs using BIP69 and cltv expiry") {
    val localFundingPriv = PrivateKey.fromHex("a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1")
    val remoteFundingPriv = PrivateKey.fromHex("a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2")
    val localRevocationPriv = PrivateKey.fromHex("a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3")
    val localPaymentPriv = PrivateKey.fromHex("a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4")
    val localDelayedPaymentPriv = PrivateKey.fromHex("a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5")
    val remotePaymentPriv = PrivateKey.fromHex("a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6")
    val localHtlcPriv = PrivateKey.fromHex("a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7")
    val remoteHtlcPriv = PrivateKey.fromHex("a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8")
    val commitInput = Funding.makeFundingInputInfo(new ByteVector32("a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0"), 0, Btc(1), localFundingPriv.publicKey, remoteFundingPriv.publicKey)

    // htlc1 and htlc2 are two regular incoming HTLCs with different amounts.
    // htlc2 and htlc3 have the same amounts and should be sorted according to their scriptPubKey
    // htlc4 is identical to htlc3 and htlc5 has same payment_hash/amount but different CLTV
    val paymentPreimage1 = new ByteVector32("1111111111111111111111111111111111111111111111111111111111111111")
    val paymentPreimage2 = new ByteVector32("2222222222222222222222222222222222222222222222222222222222222222")
    val paymentPreimage3 = new ByteVector32("3333333333333333333333333333333333333333333333333333333333333333")
    val htlc1 = UpdateAddHtlc(randomBytes32, 1, MilliBtc(100).toMilliSatoshi, sha256(paymentPreimage1), CltvExpiry(300), TestConstants.emptyOnionPacket)
    val htlc2 = UpdateAddHtlc(randomBytes32, 2, MilliBtc(200).toMilliSatoshi, sha256(paymentPreimage2), CltvExpiry(300), TestConstants.emptyOnionPacket)
    val htlc3 = UpdateAddHtlc(randomBytes32, 3, MilliBtc(200).toMilliSatoshi, sha256(paymentPreimage3), CltvExpiry(300), TestConstants.emptyOnionPacket)
    val htlc4 = UpdateAddHtlc(randomBytes32, 4, MilliBtc(200).toMilliSatoshi, sha256(paymentPreimage3), CltvExpiry(300), TestConstants.emptyOnionPacket)
    val htlc5 = UpdateAddHtlc(randomBytes32, 5, MilliBtc(200).toMilliSatoshi, sha256(paymentPreimage3), CltvExpiry(301), TestConstants.emptyOnionPacket)

    val spec = CommitmentSpec(
      htlcs = Set(
        OutgoingHtlc(htlc1),
        OutgoingHtlc(htlc2),
        OutgoingHtlc(htlc3),
        OutgoingHtlc(htlc4),
        OutgoingHtlc(htlc5)
      ),
      feeratePerKw = feeratePerKw,
      toLocal = MilliBtc(400).toMilliSatoshi,
      toRemote = MilliBtc(300).toMilliSatoshi)

    val commitTxNumber = 0x404142434446L
    val (commitTx, outputs) = {
      val outputs = makeCommitTxOutputs(localIsFunder = true, localDustLimit, localRevocationPriv.publicKey, toLocalDelay, localDelayedPaymentPriv.publicKey, remotePaymentPriv.publicKey, localHtlcPriv.publicKey, remoteHtlcPriv.publicKey, localFundingPriv.publicKey, remoteFundingPriv.publicKey, spec, DefaultCommitmentFormat)
      val txinfo = makeCommitTx(commitInput, commitTxNumber, localPaymentPriv.publicKey, remotePaymentPriv.publicKey, localIsFunder = true, outputs)
      val localSig = Transactions.sign(txinfo, localPaymentPriv, TxOwner.Local, DefaultCommitmentFormat)
      val remoteSig = Transactions.sign(txinfo, remotePaymentPriv, TxOwner.Remote, DefaultCommitmentFormat)
      (Transactions.addSigs(txinfo, localFundingPriv.publicKey, remoteFundingPriv.publicKey, localSig, remoteSig), outputs)
    }

    // htlc1 comes before htlc2 because of the smaller amount (BIP69)
    // htlc2 and htlc3 have the same amount but htlc2 comes first because its pubKeyScript is lexicographically smaller than htlc3's
    // htlc5 comes after htlc3 and htlc4 because of the higher CLTV
    val htlcOut1 :: htlcOut2 :: htlcOut3 :: htlcOut4 :: htlcOut5 :: _ = commitTx.tx.txOut.toList
    assert(htlcOut1.amount == 10000000.sat)
    for (htlcOut <- Seq(htlcOut2, htlcOut3, htlcOut4, htlcOut5)) {
      assert(htlcOut.amount == 20000000.sat)
    }

    assert(htlcOut2.publicKeyScript.toHex < htlcOut3.publicKeyScript.toHex)
    assert(outputs.find(_.commitmentOutput == OutHtlc(OutgoingHtlc(htlc2))).map(_.output.publicKeyScript).contains(htlcOut2.publicKeyScript))
    assert(outputs.find(_.commitmentOutput == OutHtlc(OutgoingHtlc(htlc3))).map(_.output.publicKeyScript).contains(htlcOut3.publicKeyScript))
    assert(outputs.find(_.commitmentOutput == OutHtlc(OutgoingHtlc(htlc4))).map(_.output.publicKeyScript).contains(htlcOut4.publicKeyScript))
    assert(outputs.find(_.commitmentOutput == OutHtlc(OutgoingHtlc(htlc5))).map(_.output.publicKeyScript).contains(htlcOut5.publicKeyScript))
  }

  test("BOLT 3 fee tests") {
    val dustLimit = 546 sat
    val bolt3 = {
      val fetch = Source.fromURL("https://raw.githubusercontent.com/lightningnetwork/lightning-rfc/master/03-transactions.md")
      // We'll use character '$' to separate tests:
      val formatted = fetch.mkString.replace("    name:", "$   name:")
      fetch.close()
      formatted
    }

    def htlcIn(amount: Satoshi): DirectedHtlc = IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, amount.toMilliSatoshi, ByteVector32.Zeroes, CltvExpiry(144), TestConstants.emptyOnionPacket))

    def htlcOut(amount: Satoshi): DirectedHtlc = OutgoingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, amount.toMilliSatoshi, ByteVector32.Zeroes, CltvExpiry(144), TestConstants.emptyOnionPacket))

    case class TestVector(name: String, spec: CommitmentSpec, expectedFee: Satoshi)

    // this regex extract params from a given test
    val testRegex = ("""name: (.*)\n""" +
      """.*to_local_msat: ([0-9]+)\n""" +
      """.*to_remote_msat: ([0-9]+)\n""" +
      """.*feerate_per_kw: ([0-9]+)\n""" +
      """.*base commitment transaction fee = ([0-9]+)\n""" +
      """[^$]+""").r
    // this regex extracts htlc direction and amounts
    val htlcRegex = """.*HTLC [0-9] ([a-z]+) amount ([0-9]+).*""".r
    val tests = testRegex.findAllIn(bolt3).map(s => {
      val testRegex(name, to_local_msat, to_remote_msat, feerate_per_kw, fee) = s
      val htlcs = htlcRegex.findAllIn(s).map(l => {
        val htlcRegex(direction, amount) = l
        direction match {
          case "offered" => htlcOut(new Satoshi(amount.toLong))
          case "received" => htlcIn(new Satoshi(amount.toLong))
        }
      }).toSet
      TestVector(name, CommitmentSpec(htlcs, FeeratePerKw(PimpSatoshi(feerate_per_kw.toLong)), MilliSatoshi(to_local_msat.toLong), MilliSatoshi(to_remote_msat.toLong)), new Satoshi(fee.toLong))
    }).toSeq

    assert(tests.size === 15, "there were 15 tests at ec99f893f320e8c88f564c1c8566f3454f0f1f5f") // simple non-reg to make sure we are not missing tests
    tests.foreach(test => {
      logger.info(s"running BOLT 3 test: '${test.name}'")
      val fee = commitTxFee(dustLimit, test.spec, DefaultCommitmentFormat)
      assert(fee === test.expectedFee)
    })
  }

}