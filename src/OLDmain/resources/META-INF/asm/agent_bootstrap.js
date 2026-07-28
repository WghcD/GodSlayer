// 导入 ASM 节点类
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
var JumpInsnNode = Java.type('org.objectweb.asm.tree.JumpInsnNode');
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
var LabelNode = Java.type('org.objectweb.asm.tree.LabelNode');

function initializeCoreMod() {
    var transformers = {};

    // 辅助：在方法开头插入“if (钩子返回true) return;”
    function injectCheck(methodNode, hookMethod, hookDesc, isBooleanReturn) {
        var instructions = methodNode.instructions;
        var newInsns = new InsnList();
        // 加载 this
        newInsns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        // 如果有浮点参数（setHealth），加载参数
        if (hookDesc.indexOf('F') !== -1) {
            newInsns.add(new VarInsnNode(Opcodes.FLOAD, 1));
        }
        // 调用静态钩子
        newInsns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            'com/godslayercore/ProtectionInspectionForCore',
            hookMethod,
            hookDesc,
            false
        ));
        var label = new LabelNode();
        newInsns.add(new JumpInsnNode(Opcodes.IFEQ, label));
        // 返回
        if (isBooleanReturn) {
            newInsns.add(new InsnNode(Opcodes.ICONST_0));
            newInsns.add(new InsnNode(Opcodes.IRETURN));
        } else {
            newInsns.add(new InsnNode(Opcodes.RETURN));
        }
        newInsns.add(label);
        instructions.insert(newInsns);
        return methodNode;
    }

    // 1. Entity.remove
    transformers['entity_remove'] = {
        target: {
            type: 'METHOD',
            class: 'net.minecraft.world.entity.Entity',
            methodName: 'remove',
            methodDesc: '(Lnet/minecraft/world/entity/Entity$RemovalReason;)V'
        },
        transformer: function(methodNode) {
            return injectCheck(methodNode,
                'shouldBlockRemove',
                '(Lnet/minecraft/world/entity/Entity;)Z',
                false
            );
        }
    };

    // 2. Entity.discard
    transformers['entity_discard'] = {
        target: {
            type: 'METHOD',
            class: 'net.minecraft.world.entity.Entity',
            methodName: 'discard',
            methodDesc: '()V'
        },
        transformer: function(methodNode) {
            return injectCheck(methodNode,
                'shouldBlockRemove',
                '(Lnet/minecraft/world/entity/Entity;)Z',
                false
            );
        }
    };

    // 3. Entity.tick
    transformers['entity_tick'] = {
        target: {
            type: 'METHOD',
            class: 'net.minecraft.world.entity.Entity',
            methodName: 'tick',
            methodDesc: '()V'
        },
        transformer: function(methodNode) {
            return injectCheck(methodNode,
                'shouldBlockTick',
                '(Lnet/minecraft/world/entity/Entity;)Z',
                false
            );
        }
    };

    // 4. LivingEntity.hurt
    transformers['living_hurt'] = {
        target: {
            type: 'METHOD',
            class: 'net.minecraft.world.entity.LivingEntity',
            methodName: 'hurt',
            methodDesc: '(Lnet/minecraft/world/damagesource/DamageSource;F)Z'
        },
        transformer: function(methodNode) {
            return injectCheck(methodNode,
                'shouldBlockHurt',
                '(Lnet/minecraft/world/entity/LivingEntity;)Z',
                true
            );
        }
    };

    // 5. LivingEntity.setHealth
    transformers['living_sethealth'] = {
        target: {
            type: 'METHOD',
            class: 'net.minecraft.world.entity.LivingEntity',
            methodName: 'setHealth',
            methodDesc: '(F)V'
        },
        transformer: function(methodNode) {
            return injectCheck(methodNode,
                'shouldBlockSetHealth',
                '(Lnet/minecraft/world/entity/LivingEntity;F)Z',
                false
            );
        }
    };

    return transformers;
}