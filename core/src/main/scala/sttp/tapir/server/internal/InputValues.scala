package sttp.tapir.server.internal

import sttp.tapir.internal.{CombineParams, Params, ParamsAsAny}
import sttp.tapir.{DecodeResult, EndpointIO, EndpointInput, Mapping}

sealed trait InputValuesResult
object InputValuesResult {
  case class Value(params: Params, remainingBasicValues: Vector[Any]) extends InputValuesResult
  case class Failure(input: EndpointInput[_], failure: DecodeResult.Failure, rootInput: EndpointInput[_]) extends InputValuesResult
}

object InputValues {

  /** Returns the value of the input, tupled and mapped as described by the data structure. Values of basic inputs
    * are taken as consecutive values from `values.basicInputsValues`. Hence, these should match (in order).
    */
  def apply(input: EndpointInput[_], values: DecodeInputsResult.Values): InputValuesResult =
    apply(input, input, values.basicInputsValues)

  private def apply(input: EndpointInput[_], rootInput: EndpointInput[_], remainingBasicValues: Vector[Any]): InputValuesResult = {
    input match {
      case EndpointInput.Pair(left, right, combine, _) => handlePair(left, right, rootInput, combine, remainingBasicValues)
      case EndpointIO.Pair(left, right, combine, _)    => handlePair(left, right, rootInput, combine, remainingBasicValues)
      case EndpointInput.MappedPair(wrapped, codec)    => handleMappedPair(wrapped, rootInput, codec, remainingBasicValues)
      case EndpointIO.MappedPair(wrapped, codec)       => handleMappedPair(wrapped, rootInput, codec, remainingBasicValues)
      case auth: EndpointInput.Auth[_]                 => apply(auth.input, rootInput, remainingBasicValues)
      case _: EndpointInput.Basic[_] =>
        remainingBasicValues match {
          case v +: valuesTail => InputValuesResult.Value(ParamsAsAny(v), valuesTail)
          case Vector() =>
            throw new IllegalStateException(s"Mismatch between basic input values: $remainingBasicValues, and basic inputs in: $input")
        }
    }
  }

  private def handlePair(
      left: EndpointInput[_],
      right: EndpointInput[_],
      rootInput: EndpointInput[_],
      combine: CombineParams,
      remainingBasicValues: Vector[Any]
  ): InputValuesResult = {
    apply(left, rootInput, remainingBasicValues) match {
      case InputValuesResult.Value(leftParams, remainingBasicValues2) =>
        apply(right, rootInput, remainingBasicValues2) match {
          case InputValuesResult.Value(rightParams, remainingBasicValues3) =>
            InputValuesResult.Value(combine(leftParams, rightParams), remainingBasicValues3)
          case f2: InputValuesResult.Failure => f2
        }
      case f: InputValuesResult.Failure => f
    }
  }

  private def handleMappedPair[II, T](
      wrapped: EndpointInput[II],
      rootInput: EndpointInput[_],
      codec: Mapping[II, T],
      remainingBasicValues: Vector[Any]
  ): InputValuesResult = {
    apply(wrapped, rootInput, remainingBasicValues) match {
      case InputValuesResult.Value(pairValue, remainingBasicValues2) =>
        codec.decode(pairValue.asAny.asInstanceOf[II]) match {
          case DecodeResult.Value(v)   => InputValuesResult.Value(ParamsAsAny(v), remainingBasicValues2)
          case f: DecodeResult.Failure => InputValuesResult.Failure(wrapped, f, rootInput)
        }
      case f: InputValuesResult.Failure => f
    }
  }
}
