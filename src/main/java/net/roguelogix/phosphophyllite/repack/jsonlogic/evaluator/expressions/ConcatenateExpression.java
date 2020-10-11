package net.roguelogix.phosphophyllite.repack.jsonlogic.evaluator.expressions;

import net.roguelogix.phosphophyllite.repack.jsonlogic.evaluator.JsonLogicEvaluationException;

import java.util.List;
import java.util.stream.Collectors;

public class ConcatenateExpression implements PreEvaluatedArgumentsExpression {
  public static final ConcatenateExpression INSTANCE = new ConcatenateExpression();

  private ConcatenateExpression() {
    // Use INSTANCE instead.
  }

  @Override
  public String key() {
    return "cat";
  }

  @Override
  public Object evaluate(List arguments, Object data) throws JsonLogicEvaluationException {
    return arguments.stream()
      .map(obj -> {
        if (obj instanceof Double && obj.toString().endsWith(".0")) {
          return ((Double) obj).intValue();
        }

        return obj;
      })
      .map(Object::toString)
      .collect(Collectors.joining());
  }
}
