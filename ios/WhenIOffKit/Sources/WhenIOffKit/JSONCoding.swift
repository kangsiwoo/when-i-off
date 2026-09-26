import Foundation

/// 백엔드와 주고받는 JSON의 인코더/디코더. 시각은 ``Timestamp`` 규칙을 쓴다.
///
/// 백엔드는 `spring.jackson.default-property-inclusion: non_null`이라 null 필드를 **아예 빼고** 보낸다.
/// 모델의 선택 필드는 모두 `Optional`이라 합성된 `Decodable`이 키 부재를 `nil`로 받는다.
/// 반대로 보낼 때도 `nil`은 키를 빼는데(`encodeIfPresent`), PATCH가 "보낸 필드만 바꾼다"는
/// 백엔드 의미와 맞는다.
enum WhenIOffJSON {
    static func decoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let text = try container.decode(String.self)
            guard let date = Timestamp.parse(text) else {
                throw DecodingError.dataCorruptedError(
                    in: container, debugDescription: "not an ISO-8601 instant: \(text)")
            }
            return date
        }
        return decoder
    }

    static func encoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(Timestamp.format(date))
        }
        // 키 순서를 고정해 같은 요청이 같은 바이트가 되게 한다 (테스트와 로그 비교용).
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }
}
