package com.unisage.backend.service.conversation;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.unisage.backend.entity.Message;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: Hibernate's JSON mapper casts a field declared as plain {@code Object} to String when
 * binding a jsonb column, so persisting a list of citations threw ClassCastException (HTTP 500 on
 * PATCH /messages/{id}). The field must keep a concrete generic collection type, like {@code metadata}.
 */
class MessageCitationsTypeTest {

    @Test
    void citationsIsDeclaredAsListOfMapsNotObject() throws NoSuchFieldException {
        Field field = Message.class.getDeclaredField("citations");

        assertThat(field.getType()).isEqualTo(List.class);
        Type generic = field.getGenericType();
        assertThat(generic).isInstanceOf(ParameterizedType.class);
        assertThat(((ParameterizedType) generic).getActualTypeArguments()[0].getTypeName())
                .isEqualTo(Map.class.getTypeName() + "<java.lang.String, java.lang.Object>");
    }
}
