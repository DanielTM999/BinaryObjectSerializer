# Binary Object Mapper

Biblioteca para **serializar e desserializar dados em arrays de bytes** de forma eficiente, permitindo transmitir ou armazenar informações complexas de forma binária. Suporta tipos primitivos, objetos complexos, arrays e coleções genéricas.

Esta biblioteca pode ser usada com **encoders e decoders separados** ou com um **`BinaryObjectMapper` unificado** que combina ambos.

---

## Recursos

- Serialização e desserialização de:
    - Tipos primitivos (`int`, `long`, `double`, `boolean`, etc.)
    - Strings
    - Arrays (`byte[]`, `int[]`, `Object[]`, etc.)
    - Coleções (`List`, `Set`) via wrapper genérico `CollectionReference<T>`
    - Objetos complexos com campos aninhados
    - `BigInteger` e `BigDecimal`
- Suporte a valores nulos e arrays vazios
- Mensagens de erro detalhadas indicando tipo esperado e tipo encontrado
- Conversão bidirecional consistente (`encode → decode → encode` mantém os mesmos bytes)

---

## Conceitos Principais

### Encoder

Converte qualquer objeto compatível em um **array de bytes**.

```java
import dtm.serialization.mapper.BinaryObjectEncoderMapper;

BinaryObjectEncoderMapper encoder = new BinaryObjectEncoderMapper();
byte[] bytes = encoder.encodeToByteArray(obj);
```

### Encoder

Converte qualquer objeto compatível em um **array de bytes**.

```java
import dtm.serialization.CollectionReference;
import dtm.serialization.mapper.BinaryObjectDecoderMapper;

BinaryObjectDecoderMapper decoder = new BinaryObjectDecoderMapper();

MyClass obj = decoder.readAsObject(bytes, MyClass.class);
List<String> list = decoder.readAsCollection(bytes, new CollectionReference<List<String>>() {});
```

### Acompanhar o progresso da desserialização

Passe um `DescriptorObserver` para receber eventos de início, progresso e término de cada descritor. Os callbacks são entregues de forma assíncrona e ordenada, sem bloquear a thread que está desserializando.

```java
import dtm.serialization.DescriptorEvent;
import dtm.serialization.DescriptorObserver;

DescriptorObserver observer = new DescriptorObserver() {
    @Override
    public void onDescriptorStarted(DescriptorEvent event) {
        System.out.println("Iniciando: " + event.descriptorName());
    }

    @Override
    public void onProgress(DescriptorEvent event) {
        System.out.printf("Progresso: %.2f%%%n", event.percentage());
    }

    @Override
    public void onDescriptorFinished(DescriptorEvent event) {
        System.out.println("Finalizado: " + event.descriptorName());
    }
};

MyClass value = decoder.readAsObject(bytes, MyClass.class, observer);
```

### Conteudo grande em streaming

Campos que podem conter arquivos maiores que a memoria disponivel devem usar
`StreamContent` e `@LargeContent`. O tamanho precisa ser conhecido antes do
encode. O encoder e o decoder transferem o corpo em blocos de 64 KiB.

```java
import dtm.serialization.StreamContent;

public class Upload {
    public String name;

    @LargeContent
    public StreamContent file;
}

Upload upload = new Upload();
upload.name = "video.mp4";
upload.file = StreamContent.from(Path.of("video.mp4"));

try (OutputStream network = socket.getOutputStream()) {
    mapper.encode(upload, network);
}
```

No decode, um resolver escolhe o destino de cada campo grande. O valor gravado
e reabrivel pelo `StreamContent` atribuido ao objeto:

```java
DecodeOptions options = DecodeOptions.DEFAULT.withLargeContentResolver(context -> {
    Path destination = uploadsDirectory.resolve(context.fieldName());
    return LargeContentDestination.to(destination);
});

Upload upload = mapper.readAsObjectWithOptions(networkInput, Upload.class, options);
try (InputStream file = upload.file.openStream()) {
    // consumir o arquivo salvo
}
```

As streams dos campos `@LargeContent` sao fechadas pela biblioteca. Uma
`InputStream` ou `OutputStream` principal fornecida diretamente pelo chamador
permanece aberta. Cada decode consome exatamente um frame e deixa um eventual
frame seguinte na stream.

O encoder atual produz o protocolo v4, com tamanhos `long`. O decoder continua
aceitando os protocolos v2 e v3. Tipos em memoria, como `byte[]` e `String`,
continuam sujeitos ao limite de arrays da JVM; use `StreamContent` para dados
potencialmente grandes.

`StreamContent` pode ser estendido. Sem resolver, uma subclasse declarada no
campo deve possuir um construtor sem argumentos ou um construtor
`(long, IOSupplier<InputStream>)`. O decoder tenta primeiro o construtor vazio e,
se ele nao existir, usa o construtor com tamanho e origem; nos dois casos conecta
a instancia ao ciclo de vida do arquivo temporario. Se nenhum desses construtores
existir ou se a construcao falhar, o decode termina com
`DecodeSerializationException`. Quando um resolver for usado,
`completedContent()` deve devolver uma instancia compativel com o tipo do campo.

Se nenhum `LargeContentResolver` for configurado, o decoder copia o campo em
chunks para um arquivo temporario e devolve um `StreamContent` lazy. Chame
`close()` nesse valor quando terminar para remover o arquivo temporario. Com um
resolver, o destino e o ciclo de vida do arquivo ficam sob controle da aplicacao.
Nos dois casos, a copia termina no limite declarado e o decoder continua
normalmente pelos campos posteriores.

Para conteudos menores que devem permanecer em memoria, declare o campo como
`StreamLazy` sem `@LargeContent`. O mesmo descritor de streaming e usado no
protocolo, mas o decoder armazena os bytes em memoria e `openStream()` devolve
uma nova stream a cada chamada:

```java
public class Message {
    public StreamLazy content;
}

Message message = new Message();
message.content = StreamLazy.of(bytes);
```

`StreamLazy` esta sujeito ao limite de arrays da JVM e mantem todo o conteudo em
memoria ate `close()` ou ate a instancia ser coletada. Se o campo tiver
`@LargeContent`, o comportamento externo/temporario continua tendo prioridade.

### Arvore sob demanda

Ao ler um `BinaryObjectNode` de uma stream, e possivel decodificar o objeto de
headers e deixar o corpo binario terminal ligado diretamente a stream original:

```java
DecodeOptions options = DecodeOptions.DEFAULT.withDeserializeOnDemand(true);
BinaryObjectNode tree = mapper.readAsTreeWithOptions(input, options);

BinaryObjectNode headers = tree.getChild("headers");
BinaryObjectNode payload = tree.getChild("payload");

ObjectType bodyType = payload.getObjectType();
long bodyLength = payload.getBodyLength();
```

O tipo do descritor informa se o corpo recebido e `BYTES` ou `LARGE_CONTENT`.
Nos dois casos ele pode ser consumido sem copia usando `openStream()`:

```java
try (tree; InputStream body = payload.openStream()) {
    body.transferTo(destination);
}
```

O corpo sob demanda e one-shot: `openStream()` pode ser chamado uma vez. O
decoder para exatamente no inicio desse corpo, portanto ele precisa ser
consumido ou fechado antes de ler o proximo frame da stream principal. Fechar o
`BinaryObjectNode` raiz tambem descarta o restante do corpo e posiciona a origem
no proximo frame.

Como o protocolo intercala cada header com seu corpo, somente um `BYTES` ou
`LARGE_CONTENT` que seja o ultimo descritor do frame pode permanecer lazy sem
buffer ou arquivo temporario. Corpos anteriores sao consumidos normalmente para
que o decoder consiga chegar aos headers seguintes. `getAsBytes()` materializa
o corpo selecionado em memoria e continua limitado a `Integer.MAX_VALUE`;
`openStream()` aceita tamanhos `long`.

Nos objetos gerados por este encoder, os campos sao ordenados pelo nome do campo
Java. Portanto, o campo usado como corpo streaming deve ficar por ultimo nessa
ordem. No modo sob demanda, o corpo terminal e lido diretamente da origem e nao
passa pelo `LargeContentResolver`.

A opcao afeta apenas `readAsTree*`; `readAsObject*` e `readAsCollection*`
continuam materializando o resultado completo.

Por padrão, quando `getExecutorService()` retorna `null`, o decoder usa `ForkJoinPool.commonPool()` e nunca o encerra.

Um executor fornecido pelo observer é encerrado depois do último callback por padrão. Para compartilhar o executor ou reutilizar o observer, sobrescreva `shouldAutoCloseExecutorService()` e retorne `false`.
