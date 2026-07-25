// Flashcards hardcodeadas por materia (id → array de { term, definition })
const flashcardsData = {
	1: [
		{ term: 'Algoritmo', definition: 'Secuencia finita de instrucciones bien definidas para resolver un problema.' },
		{ term: 'Complejidad temporal', definition: 'Medida del tiempo que tarda un algoritmo en función del tamaño de la entrada, expresada en notación Big-O.' },
		{ term: 'Lista enlazada', definition: 'Estructura de datos donde cada nodo contiene un valor y un puntero al siguiente nodo.' },
		{ term: 'Árbol binario', definition: 'Estructura jerárquica donde cada nodo tiene como máximo dos hijos: izquierdo y derecho.' },
		{ term: 'Recursión', definition: 'Técnica en la que una función se llama a sí misma para resolver subproblemas más pequeños.' },
		{ term: 'Hash table', definition: 'Estructura que mapea claves a valores usando una función hash para acceso en O(1) promedio.' },
		{ term: 'Ordenamiento por burbuja', definition: 'Algoritmo O(n²) que compara e intercambia elementos adyacentes repetidamente hasta ordenar la lista.' },
		{ term: 'Pila (Stack)', definition: 'Estructura LIFO (Last In, First Out): el último elemento insertado es el primero en salir.' },
		{ term: 'Cola (Queue)', definition: 'Estructura FIFO (First In, First Out): el primer elemento insertado es el primero en salir.' },
		{ term: 'Grafo', definition: 'Conjunto de nodos (vértices) conectados por aristas (edges), puede ser dirigido o no dirigido.' },
	],
	2: [
		{ term: 'Base de datos relacional', definition: 'Sistema que organiza datos en tablas con filas y columnas, relacionadas mediante claves.' },
		{ term: 'Clave primaria', definition: 'Atributo o conjunto de atributos que identifican de forma única cada fila de una tabla.' },
		{ term: 'Clave foránea', definition: 'Atributo que referencia la clave primaria de otra tabla para establecer una relación.' },
		{ term: 'Normalización', definition: 'Proceso de organizar una base de datos para reducir redundancia y mejorar la integridad de los datos.' },
		{ term: 'SQL', definition: 'Structured Query Language: lenguaje estándar para consultar y manipular bases de datos relacionales.' },
		{ term: 'JOIN', definition: 'Operación SQL que combina filas de dos o más tablas basándose en una condición relacionada.' },
		{ term: 'Índice', definition: 'Estructura auxiliar que acelera las consultas a una tabla a costa de espacio adicional de almacenamiento.' },
		{ term: 'Transacción', definition: 'Unidad de trabajo que se ejecuta de forma atómica: o se completa toda o no se aplica ningún cambio (ACID).' },
		{ term: 'Vista (View)', definition: 'Consulta almacenada que actúa como una tabla virtual, simplificando consultas complejas.' },
		{ term: 'Modelo entidad-relación', definition: 'Diagrama conceptual que representa entidades, sus atributos y las relaciones entre ellas.' },
	],
	3: [
		{ term: 'Dirección IP', definition: 'Identificador numérico único asignado a cada dispositivo en una red, en formato IPv4 (32 bits) o IPv6 (128 bits).' },
		{ term: 'Máscara de subred', definition: 'Valor de 32 bits que divide una dirección IP en parte de red y parte de host.' },
		{ term: 'Subnetting', definition: 'Técnica de dividir una red en subredes más pequeñas para optimizar el uso de direcciones IP.' },
		{ term: 'Router', definition: 'Dispositivo de capa 3 que reenvía paquetes entre redes distintas usando tablas de enrutamiento.' },
		{ term: 'Protocolo TCP', definition: 'Protocolo de transporte orientado a conexión que garantiza entrega ordenada y sin errores mediante confirmaciones (ACK).' },
		{ term: 'Protocolo UDP', definition: 'Protocolo de transporte sin conexión, más rápido que TCP pero sin garantía de entrega ni orden.' },
		{ term: 'Fragmentación', definition: 'División de un paquete IP en fragmentos más pequeños para adaptarse al MTU de la red.' },
		{ term: 'MTU', definition: 'Maximum Transmission Unit: tamaño máximo en bytes de un paquete que puede transmitirse en una red.' },
		{ term: 'Tabla de enrutamiento', definition: 'Estructura en un router que lista destinos de red y la interfaz/siguiente salto para alcanzarlos.' },
		{ term: 'DNS', definition: 'Domain Name System: sistema que traduce nombres de dominio legibles (ej: google.com) a direcciones IP.' },
	],
}

export default flashcardsData
