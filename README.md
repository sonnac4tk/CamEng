# CamEng – câmera de trabalho

App Android (feito pensando no Galaxy A54) para fotos de trabalho que **não se misturam
com a galeria pessoal** e **sobem sozinhas para o Google Drive**.

## O que o app faz

| Função | Como usar |
|---|---|
| Fotos separadas da galeria | Ficam na pasta privada do app. A Galeria e o Google Fotos não as veem. Para ver, use o botão **🖼 Fotos** do app. |
| Envio automático ao Drive | Quando houver internet (Wi-Fi **ou** dados móveis), envia para `Meu Drive/CamEng/AAAA-MM-DD/`. Funciona com o app fechado. |
| Zoom | Movimento de pinça na tela ou barra de zoom. O botão **1x** volta ao normal. |
| Foco | Toque na tela no ponto desejado. |
| Lanterna | Botão **🔦**. Fica ligada até você desligar. |
| Câmera lenta | Botão **🐢**. Grava a 120 fps (vídeo 4x mais lento). Funciona com e sem lanterna. |
| Data e hora | Gravadas no canto inferior direito de toda foto. |
| Comentário | Digite no campo de cima. Ele sai na foto junto com a data/hora e fica guardado para as próximas fotos (o **✕** limpa). |
| Régua / escala | Botão **📏 Régua**. Arraste as pontas para esticar/girar e o meio para mover. A régua sai gravada na foto. |

### Nome dos arquivos

`horas-minutos-segundos - dia-mês-ano`, por exemplo `14-32-05 - 03-11-2026.jpg`.
Vídeos em câmera lenta: `14-32-05 - 03-11-2026 (lento).mp4`.

### Modos da régua

- **Virtual**: barra com divisões (4 a 100) sem medida real, só para comparação.
- **Real (automática)**: o app estima a escala em mm pela distância do foco da câmera.
  Toque no objeto para focar antes. É uma **estimativa** (normalmente ±10–20%),
  depende do celular e só funciona com zoom ≥ 1x.
- **Real (calibrada)**: a forma mais precisa. Coloque no mesmo plano um objeto de medida
  conhecida (cartão de crédito, moeda de R$ 1, folha A4 ou outra medida que você digitar),
  ajuste as pontas da régua nas bordas dele e toque em **Calibrar**. Depois a régua mostra
  milímetros reais. Se só o zoom mudar, a calibração continua valendo. Se a distância mudar,
  calibre de novo.

> Atenção: desinstalar o app apaga as fotos guardadas nele. As que já foram para o Drive
> continuam no Drive. Na tela **🖼 Fotos** cada item mostra *☁ enviado* ou *⏳ pendente*.

---

## 1. Registrar o app no Google Cloud (uma vez só, grátis)

O Google exige isso para que um app possa enviar arquivos para o seu Drive.

1. Acesse <https://console.cloud.google.com/> com a **conta Google que vai receber as fotos**.
2. Crie um projeto (ex.: `CamEng`).
3. **APIs e serviços → Biblioteca**: procure **Google Drive API** e clique em **Ativar**.
4. **APIs e serviços → Tela de consentimento OAuth** (ou "Google Auth Platform"):
   - Tipo de usuário: **Externo**. Nome do app: `CamEng`. Coloque seu e-mail.
   - Escopos: adicione `.../auth/drive.file`. Esse escopo só dá acesso aos arquivos que o
     próprio app cria, não ao resto do seu Drive.
   - Em **Público-alvo / Status de publicação**, clique em **Publicar app** (produção).
     Com o escopo `drive.file` não é preciso verificação do Google. Se ficar em "Teste",
     a autorização expira a cada 7 dias.
5. **APIs e serviços → Credenciais → Criar credenciais → ID do cliente OAuth**:
   - Tipo: **Android**
   - Nome do pacote: `com.cameng.app`
   - Impressão digital SHA-1:

     ```
     DE:36:8C:7E:58:B3:BB:A3:63:05:7C:C8:48:67:98:A8:EF:5F:98:B4
     ```

   Esse SHA-1 é da chave `app/cameng.keystore` deste repositório. Ela é usada em todo
   build, então o valor não muda.

Não é preciso copiar nenhuma chave para dentro do app. O Google reconhece o app pelo
pacote + SHA-1.

## 2. Instalar no celular

1. No GitHub, abra a aba **Actions** deste repositório e entre no último build
   **Build APK** que tenha ✅.
2. Em **Artifacts**, baixe **CamEng-apk** (vem um .zip; dentro está `app-debug.apk`).
3. Passe o APK para o celular (ou baixe direto pelo navegador do celular) e abra.
   O Android vai pedir para **permitir instalar apps desta fonte**: permita.
4. Abra o **CamEng**, permita **câmera** e **notificações**.
5. Toque em **☁ Conectar Drive** (canto superior direito) e escolha sua conta.

Para compilar no computador: Android Studio → abrir a pasta do projeto → Run.
Ou na linha de comando, `./gradlew assembleDebug`.

## Sobre a câmera lenta no A54

O app grava a **120 fps** (4x mais lento) usando o modo de alta velocidade da Camera2.
O A54 anuncia 240 fps, mas recusa esse modo para outros apps (a Samsung o reserva
para a câmera dela), por isso ele foi retirado. Se um dia o 120 fps não estiver
disponível, o app usa o modo comum mais rápido (ex.: 60 fps = 2x).
O vídeo já sai lento em qualquer player e não tem áudio.

## Estrutura do código

```
app/src/main/java/com/cameng/app/
  MainActivity.kt        câmera de fotos (CameraX): zoom, foco, lanterna, régua, comentário
  RulerView.kt           régua arrastável na tela
  RulerRenderer.kt       desenho da régua (tela e foto usam o mesmo código)
  ScaleEstimator.kt      escala real estimada pela distância de foco
  PhotoProcessor.kt      rotação + régua + data/hora + comentário gravados no JPEG
  SlowMotionActivity.kt  vídeo em câmera lenta (Camera2 high-speed) com lanterna
  GalleryActivity.kt     fotos/vídeos do app com status de envio
  Storage.kt             pastas privadas (pendentes/ e enviados/)
  drive/                 autorização Google, API do Drive e envio em segundo plano (WorkManager)
```
