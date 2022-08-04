package com.wavesplatform.state.diffs.smart

import com.wavesplatform.TransactionGenBase
import com.wavesplatform.account.{Address, AddressScheme, KeyPair}
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.db.WithDomain
import com.wavesplatform.lang.directives.values.{StdLibVersion, V5, V6}
import com.wavesplatform.lang.script.Script
import com.wavesplatform.lang.v1.FunctionHeader
import com.wavesplatform.lang.v1.compiler.Terms.FUNCTION_CALL
import com.wavesplatform.lang.v1.compiler.TestCompiler
import com.wavesplatform.state.diffs.ENOUGH_AMT
import com.wavesplatform.test.{NumericExt, PropSpec, TestTime}
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.{GenesisTransaction, Proofs, TxVersion}
import com.wavesplatform.transaction.smart.{InvokeScriptTransaction, SetScriptTransaction}

class MaxCallableComplexityTest extends PropSpec with WithDomain with TransactionGenBase {

  private val time = new TestTime
  private def ts   = time.getTimestamp()

  property("max callable complexity for dApp script callable (V5) is 10000") {
    val dApp = accountGen.sample.get
    val invoker = accountGen.sample.get
    withDomain(DomainPresets.RideV6) { d =>
      val genDApp = GenesisTransaction.create(dApp.toAddress, ENOUGH_AMT, ts).explicitGet()
      val genInvoker = GenesisTransaction.create(invoker.toAddress, ENOUGH_AMT, ts).explicitGet()
      val setScript = SetScriptTransaction.selfSigned(TxVersion.V2, dApp, Some(largeScript(V5, 10000)), 0.019.waves, ts).explicitGet()
      val setLargeScript = SetScriptTransaction.selfSigned(TxVersion.V2, dApp, Some(largeScript(V5, 10001)), 0.019.waves, ts).explicitGet()

      d.appendBlock(genDApp, genInvoker, setScript)
      val invokeDiff = d.transactionDiffer(invokeScript(invoker, dApp.toAddress, "test")).resultE.explicitGet()
      invokeDiff.scriptsComplexity shouldBe 10000
      d.appendAndCatchError(setLargeScript).toString should include("Contract function (test) is too complex: 10001 > 10000")
    }
  }

  property("max callable complexity for dApp script callable (V6) is 52000") {
    val dApp = accountGen.sample.get
    val invoker = accountGen.sample.get
    withDomain(DomainPresets.RideV6) { d =>
      val genDApp = GenesisTransaction.create(dApp.toAddress, ENOUGH_AMT, ts).explicitGet()
      val genInvoker = GenesisTransaction.create(invoker.toAddress, ENOUGH_AMT, ts).explicitGet()
      val setScript = SetScriptTransaction.selfSigned(TxVersion.V2, dApp, Some(largeScript(V6, 52000)), 0.025.waves, ts).explicitGet()
      val setLargeScript = SetScriptTransaction.selfSigned(TxVersion.V2, dApp, Some(largeScript(V6, 52001)), 0.025.waves, ts).explicitGet()

      d.appendBlock(genDApp, genInvoker, setScript)
      val invokeDiff = d.transactionDiffer(invokeScript(invoker, dApp.toAddress, "test")).resultE.explicitGet()
      invokeDiff.scriptsComplexity shouldBe 52000
      d.appendAndCatchError(setLargeScript).toString should include("Contract function (test) is too complex: 52001 > 52000")
    }
  }

  property("it should be possible to invoke callable (V6) with complexity > 10000 from script V5") {
    val invoker = accountGen.sample.get
    val largeDApp = accountGen.sample.get
    val invokeDApp = accountGen.sample.get

    withDomain(DomainPresets.RideV6) { d =>
      val genInvoker = GenesisTransaction.create(invoker.toAddress, ENOUGH_AMT, ts).explicitGet()
      val genLargeDApp = GenesisTransaction.create(largeDApp.toAddress, ENOUGH_AMT, ts).explicitGet()
      val genInvokeDApp = GenesisTransaction.create(invokeDApp.toAddress, ENOUGH_AMT, ts).explicitGet()

      val setLargeScript = SetScriptTransaction.selfSigned(TxVersion.V2, largeDApp, Some(largeScript(V6, 11000)), 0.014.waves, ts).explicitGet()
      val setInvokeScript = SetScriptTransaction.selfSigned(TxVersion.V2, invokeDApp, Some(invokeScript(V5, largeDApp.toAddress)), 0.001.waves, ts).explicitGet()

      d.appendBlock(genInvoker, genLargeDApp, genInvokeDApp, setLargeScript, setInvokeScript)
      val invokeDiff = d.transactionDiffer(invokeScript(invoker, invokeDApp.toAddress, "invokeTest")).resultE.explicitGet()
      invokeDiff.scriptsComplexity shouldBe 11076
    }
  }

  private def invokeScript(version: StdLibVersion, dApp: Address): Script =
    TestCompiler(version).compileContract(
      s"""
         |@Callable(i)
         |func invokeTest() = {
         |  strict a = invoke(Address(base58'${dApp.toString}'), "test", [], [])
         |  []
         |}
         |""".stripMargin
    )

  private def largeScript(version: StdLibVersion, complexity: Int): Script = {
    val sigVerifyComplexityV5 = 201
    val sigVerifyComplexityV6 = 181
    val sigVerifyCount = version match {
      case V5 => (complexity - 1) / sigVerifyComplexityV5
      case V6 => (complexity - 1) / sigVerifyComplexityV6
    }
    val varCount = version match {
      case V5 => complexity - 1 - (sigVerifyCount * sigVerifyComplexityV5)
      case V6 => complexity - 1 - (sigVerifyCount * sigVerifyComplexityV6)
    }
    TestCompiler(version).compileContract(
      s"""
         |@Callable(i)
         |func test() = {
         |  ${(1 to sigVerifyCount).map(i => s"strict r$i = sigVerify(base58'', base58'', base58'')").mkString("\n")}
         |  ${(1 to varCount).map(i => s"strict a$i = 0").mkString("\n")}
         |  []
         |}
         |""".stripMargin
    )
  }

  private def invokeScript(invoker: KeyPair, dApp: Address, funcName: String): InvokeScriptTransaction =
    InvokeScriptTransaction.create(
      version = TxVersion.V2,
      sender = invoker.publicKey,
      dappAddress = dApp,
      fc = Some(FUNCTION_CALL(FunctionHeader.User(funcName), List.empty)),
      p = Seq.empty,
      fee = 1.waves,
      feeAssetId = Waves,
      timestamp = ts,
      proofs = Proofs.empty,
      chainId = AddressScheme.current.chainId
    ).explicitGet()
      .signWith(invoker.privateKey)
}
