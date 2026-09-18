package io.cafeai.core.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The JSON example that {@code .returning(Class)} / {@code .call(Class)} adds to the prompt. */
@DisplayName("SchemaHintBuilder")
class SchemaHintBuilderTest {

    enum Status { APPROVED, QUERIED, REJECTED }

    record Flat(String tone, boolean escalate, int count, double score, Status status) {}
    record Line(String description, String amount) {}
    record Invoice(String vendor, List<Line> lines, Line first) {}
    record Category(String name, List<Category> children) {}
    record Node(String value, Node next) {}
    record Left(String name, Right right) {}
    record Right(String name, Left left) {}

    @Test @DisplayName("scalars are examples; only an enum lists its values")
    void scalarsAndEnums() {
        assertThat(SchemaHintBuilder.build(Flat.class))
            .isEqualTo("{\"tone\":\"string\",\"escalate\":false,\"count\":0,\"score\":0,\"status\":\"APPROVED|QUERIED|REJECTED\"}");
    }

    @Test @DisplayName("nested records and lists of them are described")
    void nested() {
        assertThat(SchemaHintBuilder.build(Invoice.class)).isEqualTo(
            "{\"vendor\":\"string\","
            + "\"lines\":[{\"description\":\"string\",\"amount\":\"string\"}],"
            + "\"first\":{\"description\":\"string\",\"amount\":\"string\"}}");
    }

    @Test @DisplayName("a type that contains itself gets a finite hint instead of recursing forever")
    void selfReferencing() {
        assertThat(SchemaHintBuilder.build(Category.class)).startsWith("{\"name\":\"string\",\"children\":[");
        assertThat(SchemaHintBuilder.build(Node.class)).startsWith("{\"value\":\"string\",\"next\":");
    }

    @Test @DisplayName("two types that contain each other are finite too")
    void mutuallyRecursive() {
        assertThat(SchemaHintBuilder.build(Left.class)).startsWith("{\"name\":\"string\",\"right\":");
    }

    @Test @DisplayName("the instruction tells the model to answer with JSON only")
    void instruction() {
        assertThat(SchemaHintBuilder.instruction(Flat.class, "{}")).contains("ONLY").contains("JSON").endsWith("{}");
    }
}
