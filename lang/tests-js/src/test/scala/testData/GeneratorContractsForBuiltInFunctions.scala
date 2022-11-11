package testData

import com.wavesplatform.lang.directives.values.{StdLibVersion, V3}

class GeneratorContractsForBuiltInFunctions(val dataType: String, version: StdLibVersion) {

  def codeFromMatchingAndCase(testData: String, function: String, testDataForV3: String, testDataForGreaterV3: String): String = {
    val version = caseForVersions(testDataForV3, testDataForGreaterV3)
    s"""\n
       |@Callable(i)
       |        func expression() = {
       |            let callerTestData = $testData
       |            let valueOrUnit = $function
       |            let throwMessage = "not $dataType"
       |            let val = match(valueOrUnit) {
       |              case b:$dataType => b
       |              case _ => throwMessage.throw()
       |            }
       |            $version
       |        }
       |""".stripMargin
  }

  def codeOwnData(ownDataFunction: String, testDataForV3: String, testDataForGreaterV3: String): String = {
    val version = caseForVersions(testDataForV3, testDataForGreaterV3)
    s"""\n
       |@Callable(i)
       |        func expression() = {
       |            let valueOrUnit = $ownDataFunction
       |            let val = match(valueOrUnit) {
       |              case b:$dataType => b
       |              case _ => throw("not $dataType")
       |            }
       |            $version
       |        }
       |""".stripMargin
  }

  def codeWithoutMatcher(testData: String, function: String, testDataForV3: String, testDataForGreaterV3: String): String = {
    val version = caseForVersions(testDataForV3, testDataForGreaterV3)
    s"""\n
       |@Callable(i)
       |        func expression() = {
       |            let callerTestData = $testData
       |            let val = $function
       |            $version
       |        }
       |""".stripMargin
  }

  def codeOwnDataWithoutMatcher(ownDataFunction: String, caseForVersions: String): String = {
    s"""\n
       | @Callable(i)
       |        func expression() = {
       |            let val = $ownDataFunction
       |            $caseForVersions
       |        }
       |""".stripMargin
  }

  def onlyMatcherContract(testData: String, function: String): String = {
    s"""\n
       | let callerTestData = $testData
       |        let x = match $function {
       |            case h:$dataType => h
       |            case _ => throw("not $dataType")
       |        }
       |""".stripMargin
  }

  def simpleRideCode(foo: String, bar: String, testFunction: String): String = {
    s"""\n
       |let foo = $foo
       |let bar = $bar
       |let callerTestData = $testFunction
       |""".stripMargin
  }

  def codeForDAppInvocation(byteVector: String, payment: String, func: String): String = {
    s"""\n
       |func foo(dapp2: String, a: Int, key1: String, key2: String) = {
       |        let byteVector = $byteVector
       |        let payment = $payment
       |        strict res = $func
       |        match res {
       |            case r : Int =>
       |            (
       |                [
       |                    IntegerEntry(key1, r),
       |                    IntegerEntry(key2, wavesBalance(addressFromStringValue(dapp2)).regular)
       |                ],
       |                unit
       |            )
       |                case _ => throw("Incorrect invoke result")
       |            }
       |        }
       |
       |        @Callable(i)
       |        func bar(a: Int) = {
       |        (
       |            [
       |                ScriptTransfer(i.caller, 100000000, unit)
       |            ],
       |                a * 2
       |            )
       |        }
       |""".stripMargin
  }

  def codeForAddressFromRecipient(addressOrAlias: String, func: String, address: String): String = {
    s"""
      |let addressOrAlias = $addressOrAlias;
      |        match (tx) {
      |            case t: TransferTransaction => $func == $address
      |            case _ => false
      |        }
      |""".stripMargin
  }

  private def caseForVersions(testDataForV3: String, testDataForGreaterV3: String): String = {
    if (version.id > V3.id) {
      testDataForGreaterV3
    } else {
      testDataForV3
    }
  }
}
